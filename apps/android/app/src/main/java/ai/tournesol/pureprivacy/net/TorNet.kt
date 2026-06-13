package ai.tournesol.pureprivacy.net

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Bridges the Element Call WebView (which Chromium won't let touch .onion) to the
 * box's onion services over the embedded Tor. The WebView only talks to
 * 127.0.0.1 over PLAIN http/ws (allowed from the https EC page via mixed-content),
 * so there's no local-cert problem; TLS happens only on the onion side. We rewrite
 * .onion URLs in responses (well-known rtc_foci, lk-jwt's SFU url) to localhost.
 */
object TorNet {
    private const val TAG = "PpTorNet"

    private val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    private fun trustAllCtx() = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }

    /** OkHttp reaching the onion over Tor's HTTP tunnel (CONNECT => remote DNS),
     *  trusting the box's self-signed onion certs. */
    private fun torClient(httpProxyPort: Int): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpProxyPort)))
            .sslSocketFactory(trustAllCtx().socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .retryOnConnectionFailure(true)
            .build()

    private val started = HashMap<Int, Any>()

    /** One-shot GET of an onion URL over Tor, with response-body rewrites — used by
     *  the WebView's shouldInterceptRequest to serve .onion requests Chromium would
     *  otherwise block (e.g. server-name /.well-known discovery). */
    fun fetchRewritten(url: String, httpProxyPort: Int, rewrites: Map<String, String>): Triple<String, Int, ByteArray> {
        val client = torClient(httpProxyPort)
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            var text = resp.body?.string() ?: ""
            for ((a, b) in rewrites) text = text.replace(a, b)
            val ct = (resp.header("content-type") ?: "application/json").substringBefore(";").trim()
            Log.i(TAG, "fetchRewritten $url -> ${resp.code} (${text.length}b)")
            return Triple(ct, resp.code, text.toByteArray())
        }
    }

    /** Plain-HTTP local proxy: http://127.0.0.1:localPort -> https://onion:port (over Tor),
     *  rewriting `rewrites` in response bodies. */
    fun startHttpProxy(localPort: Int, onionBase: String, httpProxyPort: Int, rewrites: Map<String, String>) {
        if (started.containsKey(localPort)) return
        val client = torClient(httpProxyPort)
        val server = object : NanoHTTPD("127.0.0.1", localPort) {
            override fun serve(session: IHTTPSession): Response {
                if (session.method == Method.OPTIONS) {
                    val r = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""); cors(r); return r
                }
                return try {
                    val url = onionBase + session.uri + (session.queryParameterString?.let { "?$it" } ?: "")
                    Log.i(TAG, "serve $localPort ${session.method} ${session.uri}")
                    val reqB = Request.Builder().url(url)
                    session.headers.forEach { (k, v) -> if (k.lowercase() !in HOP) reqB.addHeader(k, v) }
                    val m = session.method.name
                    if (m == "POST" || m == "PUT" || m == "PATCH") {
                        val files = HashMap<String, String>(); session.parseBody(files)
                        val body = files["postData"] ?: ""
                        reqB.method(m, body.toRequestBody(session.headers["content-type"]?.toMediaTypeOrNull()))
                    } else reqB.method(m, null)
                    client.newCall(reqB.build()).execute().use { resp ->
                        Log.i(TAG, "  upstream ${session.uri} -> ${resp.code}")
                        val ctType = resp.header("content-type") ?: "application/octet-stream"
                        val status = object : Response.IStatus {
                            override fun getDescription() = "" + resp.code
                            override fun getRequestStatus() = resp.code
                        }
                        // Only TEXT responses get the onion->localhost rewrite; binary
                        // (fonts, audio, wasm, images) MUST pass through byte-exact.
                        val textual = ctType.contains("json") || ctType.contains("text") ||
                            ctType.contains("javascript") || ctType.contains("xml") ||
                            ctType.contains("css") || ctType.contains("html") || ctType.contains("svg")
                        val out = if (textual) {
                            var text = resp.body?.string() ?: ""
                            for ((a, b) in rewrites) text = text.replace(a, b)
                            newFixedLengthResponse(status, ctType, text)
                        } else {
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            newFixedLengthResponse(status, ctType, java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
                        }
                        resp.headers.forEach { (k, v) ->
                            val lk = k.lowercase()
                            // strip CSP / framing / CORP so the localhost-served EC can
                            // frame + connect to our other localhost bridges.
                            if (lk !in HOP && lk != "content-type" && !lk.startsWith("access-control-") &&
                                lk != "content-security-policy" && lk != "content-security-policy-report-only" &&
                                lk != "x-frame-options" && lk != "cross-origin-embedder-policy" &&
                                lk != "cross-origin-opener-policy" && lk != "cross-origin-resource-policy"
                            ) out.addHeader(k, v)
                        }
                        cors(out); out
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "proxy $localPort error", t)
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "err:${t.message}")
                }
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        started[localPort] = server
        Log.i(TAG, "http proxy 127.0.0.1:$localPort -> $onionBase  rewrites=${rewrites.size}")
    }

    /** Plain-TCP local listener (ws://127.0.0.1:localPort) bridged to the onion's
     *  wss endpoint: accept plaintext, open a TLS connection to onion:port over Tor
     *  SOCKS5 (domain => remote DNS), and pipe. Carries the SFU WebSocket. */
    fun startTlsForwarder(localPort: Int, onionHost: String, onionPort: Int, socksPort: Int) {
        if (started.containsKey(localPort)) return
        val ss = ServerSocket(); ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
        started[localPort] = ss
        thread(name = "fwd-$localPort") {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Throwable) { break }
                thread { bridge(client, onionHost, onionPort, socksPort) }
            }
        }
        Log.i(TAG, "tls forwarder ws 127.0.0.1:$localPort -> wss $onionHost:$onionPort")
    }

    private fun bridge(client: Socket, host: String, port: Int, socksPort: Int) {
        try {
            val raw = socks5(host, port, socksPort)
            val tls = trustAllCtx().socketFactory.createSocket(raw, host, port, true) as SSLSocket
            tls.startHandshake()
            val t1 = thread { copy(client.getInputStream(), tls.getOutputStream()) }
            copy(tls.getInputStream(), client.getOutputStream())
            t1.join(200); client.close(); tls.close()
        } catch (t: Throwable) { Log.d(TAG, "bridge ended: ${t.message}"); runCatching { client.close() } }
    }

    private fun socks5(host: String, port: Int, socksPort: Int): Socket {
        val tor = Socket(); tor.connect(InetSocketAddress("127.0.0.1", socksPort), 15000)
        val ti = tor.getInputStream(); val to = tor.getOutputStream()
        to.write(byteArrayOf(5, 1, 0)); to.flush(); readFully(ti, ByteArray(2), 2)
        val h = host.toByteArray(); val req = ByteArrayOutputStream()
        req.write(byteArrayOf(5, 1, 0, 3)); req.write(h.size); req.write(h)
        req.write((port ushr 8) and 0xff); req.write(port and 0xff)
        to.write(req.toByteArray()); to.flush()
        val head = ByteArray(4); readFully(ti, head, 4)
        if (head[1].toInt() != 0) throw java.io.IOException("socks connect failed ${head[1]}")
        val skip = when (head[3].toInt()) { 1 -> 6; 4 -> 18; 3 -> { val l = ByteArray(1); readFully(ti, l, 1); (l[0].toInt() and 0xff) + 2 }; else -> 0 }
        if (skip > 0) readFully(ti, ByteArray(skip), skip)
        return tor
    }

    private fun copy(inp: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(16 * 1024)
        try { while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); out.flush() } } catch (_: Throwable) {}
    }
    private fun readFully(inp: java.io.InputStream, b: ByteArray, n: Int) {
        var off = 0; while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) throw java.io.EOFException(); off += r }
    }
    private fun cors(r: NanoHTTPD.Response) {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With")
        // Chromium Private Network Access: a secure public page (call.element.io)
        // calling 127.0.0.1 is blocked unless we opt in.
        r.addHeader("Access-Control-Allow-Private-Network", "true")
        r.addHeader("Access-Control-Max-Age", "86400")
    }
    private val HOP = setOf("host", "content-length", "connection", "accept-encoding", "transfer-encoding", "keep-alive", "proxy-connection", "te", "trailer", "upgrade")
}
