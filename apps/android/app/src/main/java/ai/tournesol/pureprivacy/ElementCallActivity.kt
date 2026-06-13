package ai.tournesol.pureprivacy

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import ai.tournesol.pureprivacy.net.TorNet
import ai.tournesol.pureprivacy.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.ClientProperties
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetCapabilitiesProvider
import org.matrix.rustcomponents.sdk.generateWebviewUrl
import org.matrix.rustcomponents.sdk.getElementCallRequiredPermissions
import org.matrix.rustcomponents.sdk.makeWidgetDriver
import org.matrix.rustcomponents.sdk.newVirtualElementCallWidget
import uniffi.matrix_sdk.EncryptionSystem
import uniffi.matrix_sdk.Intent as CallIntent
import uniffi.matrix_sdk.VirtualElementCallWidgetConfig
import uniffi.matrix_sdk.VirtualElementCallWidgetProperties

/**
 * Element Call over Tor — the branded client's job. We load Element Call in a
 * WebView that (1) routes through the embedded Tor's HTTP tunnel, (2) trusts the
 * box's self-signed .onion cert, (3) allows mixed content + grants mic/camera —
 * the exact things stock Element X's WebView refuses for a plain onion. The
 * matrix-rust-sdk WidgetDriver bridges the widget postMessage API to the SDK
 * (OpenID token, call membership, E2EE keys). Media rides Tor via the SFU's
 * force-relay through coturn-at-onion (proven path).
 */
class ElementCallActivity : ComponentActivity() {
    private val TAG = "PpCall"
    private lateinit var web: WebView
    private var ecOnion = ""
    private var ecRewrites: Map<String, String> = emptyMap()

    companion object {
        const val EC_LOCAL = 11080   // -> call.element.io (Element Call app, over Tor)
        const val HS_LOCAL = 18009   // -> onion:8009 homeserver client API
        const val JWT_LOCAL = 18443  // -> onion:8443 lk-jwt
        const val SFU_LOCAL = 17443  // -> onion:7443 LiveKit SFU (wss, raw forward)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val room = MatrixRepo.currentRoom
        if (room == null) { finish(); return }

        val onion = MatrixRepo.userId.substringAfter(":")  // box homeserver onion
        ecOnion = onion

        // Local bridges: the WebView only ever talks to 127.0.0.1; we tunnel to the
        // onion over Tor (Chromium refuses .onion directly) and rewrite .onion URLs
        // in responses (well-known rtc_foci, lk-jwt's SFU url) to localhost.
        // Rewrite the onion URLs that appear in responses to our plain-http/ws
        // localhost bridges (scheme downgraded; TLS happens only on the onion side).
        val rewrites = mapOf(
            "https://$onion:8443" to "http://127.0.0.1:$JWT_LOCAL",  // lk-jwt (well-known)
            "wss://$onion:7443" to "ws://127.0.0.1:$SFU_LOCAL",      // SFU (lk-jwt response)
            "https://$onion:8009" to "http://127.0.0.1:$HS_LOCAL",   // homeserver (well-known)
            "https://$onion:8008" to "http://127.0.0.1:$HS_LOCAL",
            "http://$onion:8008" to "http://127.0.0.1:$HS_LOCAL",
        )
        ecRewrites = rewrites
        // Serve Element Call itself from localhost (over Tor) so its origin is
        // 127.0.0.1 — a secure, PRIVATE origin in Chromium. Then all its calls to
        // our other 127.0.0.1 bridges are private->private (no Private Network
        // Access block) and localhost is a secure context (getUserMedia works).
        TorNet.startHttpProxy(EC_LOCAL, "https://call.element.io", TorManager.HTTP_PORT, rewrites)
        TorNet.startHttpProxy(HS_LOCAL, "https://$onion:8009", TorManager.HTTP_PORT, rewrites)
        TorNet.startHttpProxy(JWT_LOCAL, "https://$onion:8443", TorManager.HTTP_PORT, rewrites)
        TorNet.startTlsForwarder(SFU_LOCAL, onion, 7443, TorManager.SOCKS_PORT)

        // NO global WebView proxy: a WebView proxy routes even 127.0.0.1 through Tor,
        // and Tor can't CONNECT to loopback (so the bridges were never hit). Instead
        // the WebView reaches our bridges directly on 127.0.0.1, and the bridges
        // tunnel the box's onion services over Tor. The only clearnet load is the
        // generic Element Call app bundle (call.element.io) — no peer/box metadata.

        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                @Suppress("DEPRECATION")
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                databaseEnabled = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // grant mic/camera to the Element Call widget
                    runOnUiThread { request.grant(request.resources) }
                }
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    Log.d(TAG, "EC console: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                    return true
                }
            }
        }
        setContentView(web)

        WebView.setWebContentsDebuggingEnabled(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val props = VirtualElementCallWidgetProperties(
                    elementCallUrl = "http://127.0.0.1:$EC_LOCAL",
                    widgetId = "pp-call",
                    parentUrl = "http://127.0.0.1:$EC_LOCAL",
                    fontScale = null,
                    font = null,
                    // Match the room: the shared room is unencrypted for this first
                    // connect (E2EE calls = PerParticipantKeys, a follow-on toggle).
                    encryption = EncryptionSystem.Unencrypted,
                    posthogUserId = null,
                    posthogApiHost = null,
                    posthogApiKey = null,
                    rageshakeSubmitUrl = null,
                    sentryDsn = null,
                    sentryEnvironment = null,
                )
                val config = VirtualElementCallWidgetConfig(
                    intent = CallIntent.START_CALL,
                    skipLobby = true,
                    header = null,
                    hideHeader = true,
                    preload = false,
                    appPrompt = false,
                    confineToRoom = true,
                    hideScreensharing = true,
                    controlledAudioDevices = null,
                    sendNotificationType = null,
                )
                val settings = newVirtualElementCallWidget(props, config)
                val rawUrl = generateWebviewUrl(
                    settings, room,
                    ClientProperties("ai.tournesol.pureprivacy", null, "dark")
                )
                // point the homeserver baseUrl at our local bridge (leave the onion
                // in user/room IDs untouched — only the host:port is rewritten).
                val url = rawUrl
                    .replace("https%3A%2F%2F$onion%3A8009", "http%3A%2F%2F127.0.0.1%3A$HS_LOCAL")
                    .replace("https://$onion:8009", "http://127.0.0.1:$HS_LOCAL")
                    // Element Call's in-room widget view is under /room (root '/' is the
                    // standalone "start new call" home).
                    .replace("127.0.0.1:$EC_LOCAL/#?", "127.0.0.1:$EC_LOCAL/room/#?")
                Log.i(TAG, "EC url: $url")

                val driverAndHandle = makeWidgetDriver(settings)
                val driver = driverAndHandle.driver
                val handle = driverAndHandle.handle

                // run the widget driver (bridges postMessage <-> SDK)
                launch(Dispatchers.IO) {
                    runCatching {
                        driver.run(room, object : WidgetCapabilitiesProvider {
                            override fun acquireCapabilities(capabilities: WidgetCapabilities) =
                                getElementCallRequiredPermissions(MatrixRepo.userId, MatrixRepo.deviceId)
                        })
                    }.onFailure { Log.e(TAG, "driver.run ended", it) }
                }

                // pump: SDK -> widget (toWidget messages)
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val msg = runCatching { handle.recv() }.getOrNull() ?: break
                        Log.i(TAG, "toWidget: ${msg.take(220)}")
                        withContext(Dispatchers.Main) {
                            web.evaluateJavascript("window.__ec && window.__ec().postMessage($msg, '*');", null)
                        }
                    }
                    Log.w(TAG, "recv loop ended")
                }

                // Element Call only enters widget/room mode when embedded in an iframe
                // (it checks window.parent !== window). Load a same-origin wrapper page
                // that hosts EC in an iframe + relays the widget postMessage bridge.
                val wrapper = """
                    <!doctype html><html><head>
                    <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
                    </head><body style="margin:0;background:#0E1116">
                    <iframe id="ec" src="$url"
                      allow="camera;microphone;autoplay;display-capture;clipboard-write;fullscreen"
                      style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe>
                    <script>
                      window.__ec = function(){ return document.getElementById('ec').contentWindow; };
                      window.addEventListener('message', function(e){
                        try { var d = e.data; if (!d || !d.api) return;
                          var isReq  = (d.api === 'fromWidget' && !('response' in d));
                          var isResp = (d.api === 'toWidget'   &&  ('response' in d));
                          if (isReq || isResp) ppAndroid.fromWidget(JSON.stringify(d));
                        } catch(err){}
                      });
                    </script></body></html>
                """.trimIndent()
                withContext(Dispatchers.Main) {
                    web.addJavascriptInterface(Bridge(handle), "ppAndroid")
                    web.webViewClientInjectOnLoad()
                    web.loadDataWithBaseURL("http://127.0.0.1:$EC_LOCAL/", wrapper, "text/html", "utf-8", "http://127.0.0.1:$EC_LOCAL/")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "EC setup failed", t)
            }
        }
    }

    /** widget -> SDK (fromWidget messages). */
    inner class Bridge(private val handle: org.matrix.rustcomponents.sdk.WidgetDriverHandle) {
        @JavascriptInterface
        fun fromWidget(json: String) {
            Log.i(TAG, "fromWidget: ${json.take(220)}")
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = runCatching { handle.send(json) }.getOrElse { Log.e(TAG, "send failed", it); false }
                if (!ok) Log.w(TAG, "handle.send returned false")
            }
        }
    }

    private fun WebView.webViewClientInjectOnLoad() {
        webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed()
            }
            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                val host = request.url.host ?: return null
                // Intercept Element Call's direct .onion requests (e.g. server-name
                // /.well-known discovery) — Chromium would block them; serve over Tor.
                if (host == ecOnion && request.method == "GET") {
                    return try {
                        val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                        val (ct, code, bytes) = TorNet.fetchRewritten(
                            "https://$ecOnion:8009$path", TorManager.HTTP_PORT, ecRewrites
                        )
                        val hdrs = linkedMapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Headers" to "*",
                        )
                        Log.i(TAG, "intercepted onion GET $path -> $code")
                        android.webkit.WebResourceResponse(ct, "utf-8", code, "OK", hdrs, java.io.ByteArrayInputStream(bytes))
                    } catch (t: Throwable) {
                        Log.e(TAG, "intercept failed", t); null
                    }
                }
                return null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { web.destroy() }
    }
}
