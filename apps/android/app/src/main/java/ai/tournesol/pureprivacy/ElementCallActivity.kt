package ai.tournesol.pureprivacy

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
import uniffi.matrix_sdk.NotificationType
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
    private var callRoomId: String? = null   // for clearing our RTC membership on leave
    // onion -> localhost  (applied to proxy bodies, toWidget messages, intercepts).
    private val ecRewrites: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    // localhost -> onion  (applied to fromWidget messages so the call membership we
    // PUBLISH to the room carries the real onion focus, not our private bridge URL —
    // otherwise the peer reads "127.0.0.1" and points at its own box).
    private val ecReverse: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    // call focus onions we've already started peer bridges for (the joiner connects
    // to the CALL's focus box, which for a cross-install call is the peer's box).
    private val peerFocusStarted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val focusRe = Regex("(?:wss://|https://)([a-z2-7]{56}\\.onion):(?:7443|8443)")
    // The box whose coturn carries this call's media. Starts as our own box, then is
    // pinned to the real focus by Bridge.setTurnOnion when EC's ICE config reveals the
    // turn:<onion> it actually uses (= the box whose SFU this call landed on). The TURN
    // forwarder reads this live via its onion supplier, so updates take effect at once.
    @Volatile private var turnOnion = ""

    // Connect overlay: a branded "Connecting over Tor…" cover (EC's bundle loads over
    // Tor, ~10-20s of otherwise-blank screen) that turns into a clear error+retry if
    // the call never comes up. ecConnected flips on the first widget round-trip.
    private lateinit var rootView: FrameLayout
    private var overlayStatus: TextView? = null
    private var overlayProgress: ProgressBar? = null
    private var overlayRetry: Button? = null
    @Volatile private var ecConnected = false
    private var connectTimeout: kotlinx.coroutines.Job? = null
    // elapsedRealtime at call start — anchors the peer-membership baseline window.
    @Volatile private var callStartMs = 0L
    // INK / SUNFLOWER / PAPER / PAPERDIM as ARGB ints for the (non-Compose) overlay.
    // Kept in sync with ui/theme/Theme.kt's warm "candlelight" neutrals so the call
    // surface matches the rest of the app (and the desktop).
    private val cInk = 0xFF16140F.toInt(); private val cSun = 0xFFF2B705.toInt()
    private val cPaper = 0xFFECE6D7.toInt(); private val cDim = 0xFF9A9384.toInt()
    private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

    companion object {
        const val EC_LOCAL = 11080   // -> call.element.io (Element Call app, over Tor)
        const val HS_LOCAL = 18009   // -> own homeserver client API (onion:8009)
        const val JWT_LOCAL = 18443  // -> own lk-jwt (onion:8443)
        const val SFU_LOCAL = 17443  // -> own LiveKit SFU (onion:7443, wss)
        const val JWT_PEER = 18444   // -> FOCUS (peer) lk-jwt, discovered from call state
        const val SFU_PEER = 17444   // -> FOCUS (peer) SFU
        const val TURN_LOCAL = 13478 // -> coturn (onion:3478, TCP TURN) for media over Tor
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callStartMs = android.os.SystemClock.elapsedRealtime()
        // Block screenshots / screen-recording of the call surface (release only).
        applyScreenSecurity()
        val room = MatrixRepo.currentRoom
        if (room == null) { Log.w(TAG, "no current room — finishing"); finish(); return }
        callRoomId = runCatching { room.id() }.getOrNull()

        val onion = MatrixRepo.userId.substringAfter(":")  // box homeserver onion
        ecOnion = onion
        Log.i(TAG, "call start: room=${runCatching { room.id() }.getOrNull()} box=$onion tor=${TorManager.state.value}")

        // Local bridges: the WebView only ever talks to 127.0.0.1; we tunnel to the
        // onion over Tor (Chromium refuses .onion directly) and rewrite .onion URLs
        // in responses (well-known rtc_foci, lk-jwt's SFU url) to localhost.
        // Rewrite the onion URLs that appear in responses to our plain-http/ws
        // localhost bridges (scheme downgraded; TLS happens only on the onion side).
        // The proxies hold a reference to this SAME mutable map, so peer-focus
        // entries added later (when the joiner discovers the call's focus box)
        // take effect immediately without restarting them.
        ecRewrites.putAll(mapOf(
            "https://$onion:8443" to "http://127.0.0.1:$JWT_LOCAL",  // lk-jwt (well-known)
            "wss://$onion:7443" to "ws://127.0.0.1:$SFU_LOCAL",      // SFU (lk-jwt response)
            "https://$onion:8009" to "http://127.0.0.1:$HS_LOCAL",   // homeserver (well-known)
            "https://$onion:8008" to "http://127.0.0.1:$HS_LOCAL",
            "http://$onion:8008" to "http://127.0.0.1:$HS_LOCAL",
        ))
        ecReverse.putAll(mapOf(
            "http://127.0.0.1:$JWT_LOCAL" to "https://$onion:8443",
            "ws://127.0.0.1:$SFU_LOCAL" to "wss://$onion:7443",
            "http://127.0.0.1:$HS_LOCAL" to "https://$onion:8009",
        ))
        // Media over Tor: a TURN-over-TCP bridge to the call box's coturn. WebRTC
        // can't use turn:<onion> directly (RFC7686), so we expose it at 127.0.0.1 and
        // force the call to relay through it (see the injected patch below). The
        // forwarder targets the call's coturn box, which the joiner only learns once
        // the focus is discovered — hence the dynamic onion supplier.
        turnOnion = onion
        TorNet.startTcpForwarder(TURN_LOCAL, { turnOnion }, 3478, TorManager.SOCKS_PORT)
        // Patch injected into the Element Call page: rewrite the SFU-advertised
        // turn:<onion>:3478 ICE server to our localhost bridge (keeping the box's
        // credentials) and force iceTransportPolicy=relay so ALL media rides Tor.
        val turnPatch = """
            <script>(function(){
              var N = window.RTCPeerConnection; if (!N || N.__pp) return;
              function fix(cfg){
                cfg = cfg || {};
                var list = cfg.iceServers || [];
                list.forEach(function(s){
                  var u = s.urls; if (typeof u === 'string') u = [u];
                  if (u) s.urls = u.map(function(x){
                    // The turn:<onion> EC was handed comes from the lk-jwt of the box
                    // whose SFU this call actually uses (the focus). Report that onion
                    // to native so the TURN forwarder tunnels to the SAME box's coturn
                    // — never a stale peer focus from old call membership.
                    var m = x.match(/turns?:([a-z2-7]{56}\.onion)/i);
                    if (m) { try { ppAndroid.setTurnOnion(m[1]); } catch(e){} }
                    return x.replace(/(turns?:)[a-z2-7]{56}\.onion(:[0-9]+)?/i, '${'$'}1127.0.0.1:$TURN_LOCAL');
                  });
                });
                cfg.iceServers = list;
                cfg.iceTransportPolicy = 'relay';
                return cfg;
              }
              function P(cfg, con){ return new N(fix(cfg), con); }
              P.prototype = N.prototype; P.__pp = true;
              var oset = N.prototype.setConfiguration;
              if (oset) N.prototype.setConfiguration = function(c){ return oset.call(this, fix(c)); };
              window.RTCPeerConnection = P; window.webkitRTCPeerConnection = P;
              try { console.log('[pp] RTCPeerConnection patched -> relay via 127.0.0.1:$TURN_LOCAL'); } catch(e){}
            })();</script>
        """.trimIndent()
        // Serve Element Call itself from localhost (over Tor) so its origin is
        // 127.0.0.1 — a secure, PRIVATE origin in Chromium. Then all its calls to
        // our other 127.0.0.1 bridges are private->private (no Private Network
        // Access block) and localhost is a secure context (getUserMedia works).
        TorNet.startHttpProxy(EC_LOCAL, "https://call.element.io", TorManager.HTTP_PORT, ecRewrites, turnPatch)
        TorNet.startHttpProxy(HS_LOCAL, "https://$onion:8009", TorManager.HTTP_PORT, ecRewrites)
        TorNet.startHttpProxy(JWT_LOCAL, "https://$onion:8443", TorManager.HTTP_PORT, ecRewrites)
        TorNet.startTlsForwarder(SFU_LOCAL, onion, 7443, TorManager.SOCKS_PORT)

        // Pre-build the Tor circuits to our box's call services NOW, so when EC's
        // WebRTC fires its TURN allocation (the time-critical, late step) the onion
        // circuit is already up — otherwise a 10-30s circuit build on a real phone
        // overruns WebRTC's allocation timeout → "could not establish pc connection".
        TorNet.prewarm(onion, 3478, TorManager.SOCKS_PORT)   // coturn (TURN) — critical
        TorNet.prewarm(onion, 7443, TorManager.SOCKS_PORT)   // LiveKit SFU (wss)
        TorNet.prewarm(onion, 8443, TorManager.SOCKS_PORT)   // lk-jwt

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
                    val msg = m.message()
                    // Elevate errors/focus chatter to INFO so they survive a PpCall:I filter.
                    if (msg.contains("rror", true) || msg.contains("focus", true) || msg.contains("livekit", true))
                        Log.i(TAG, "EC console: $msg @${m.sourceId()}:${m.lineNumber()}")
                    else
                        Log.d(TAG, "EC console: $msg @${m.sourceId()}:${m.lineNumber()}")
                    return true
                }
            }
        }
        // WebView under a branded connect overlay (covers EC's slow Tor load).
        rootView = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(buildOverlay(), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setContentView(rootView)
        Log.i(TAG, "bridges up (EC=$EC_LOCAL HS=$HS_LOCAL JWT=$JWT_LOCAL SFU=$SFU_LOCAL TURN=$TURN_LOCAL); loading Element Call over Tor")
        // If EC never starts talking, surface a clear error instead of a blank screen.
        connectTimeout = lifecycleScope.launch {
            kotlinx.coroutines.delay(35_000)
            if (!ecConnected) {
                Log.w(TAG, "EC did not connect within 35s")
                showCallError("Couldn't connect the call over Tor.\nYour box or your friend's may be slow or offline.")
            }
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

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
                    // RING makes Element Call publish an m.rtc.notification when the
                    // call starts — that's what federates to the peer and lets their
                    // phone ring (the callee's checkNotifs surfaces it; the caller is
                    // already in the room so they don't ring themselves).
                    sendNotificationType = NotificationType.RING,
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
                if (BuildConfig.DEBUG) Log.i(TAG, "EC url: $url")

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
                        var msg = runCatching { handle.recv() }.getOrNull() ?: break
                        markConnected()   // EC is talking to the SDK → hide the overlay
                        // A cross-install call lives on the creator's box (the focus).
                        // Discover that focus onion from the call membership flowing
                        // down, spin up bridges to it, then rewrite every onion URL
                        // (own + peer) to the matching localhost bridge before the
                        // WebView sees it — Chromium would block the raw .onion.
                        maybeStartPeerFocus(msg)
                        maybeDetectPeerLeft(msg)
                        for ((a, b) in ecRewrites) msg = msg.replace(a, b)
                        if (BuildConfig.DEBUG && (msg.contains("foci_preferred") || msg.contains("focus_active")))
                            Log.i(TAG, "FOCUS toWidget(full): $msg")
                        if (BuildConfig.DEBUG) Log.i(TAG, "toWidget: ${msg.take(220)}")
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
                    </head><body style="margin:0;background:#16140F">
                    <iframe id="ec" src="$url"
                      allow="camera;microphone;autoplay;display-capture;clipboard-write;fullscreen"
                      style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe>
                    <script>
                      window.__ec = function(){ return document.getElementById('ec').contentWindow; };
                      window.addEventListener('message', function(e){
                        // Only accept widget messages from the Element Call iframe origin.
                        if (e.origin !== 'http://127.0.0.1:$EC_LOCAL') return;
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
                showCallError(ai.tournesol.pureprivacy.util.mapError(t))
            }
        }
    }

    /** EC is up (first widget message exchanged) → drop the connect overlay. */
    private fun markConnected() {
        if (ecConnected) return
        ecConnected = true
        connectTimeout?.cancel()
        Log.i(TAG, "EC connected (widget handshake) — hiding overlay")
        runOnUiThread { runCatching { (overlayStatus?.parent as? View)?.visibility = View.GONE } }
    }

    /** Swap the overlay into an error state with Retry/Close (UI thread-safe). */
    private fun showCallError(msg: String) {
        runOnUiThread {
            runCatching {
                overlayProgress?.visibility = View.GONE
                overlayStatus?.text = msg
                overlayStatus?.setTextColor(cPaper)
                overlayRetry?.visibility = View.VISIBLE
                (overlayStatus?.parent as? View)?.visibility = View.VISIBLE
            }
        }
    }

    /** The branded "Connecting over Tor…" overlay (becomes the error surface). */
    private fun buildOverlay(): View {
        fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(cInk)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        // The real sunflower mark (vector), not a "✿" glyph — one consistent brand.
        col.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_sunflower)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        })
        col.addView(TextView(this).apply {
            text = "PurePrivacy"; setTextColor(cPaper); setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24f))
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(28))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        overlayProgress = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(cSun) }
        col.addView(overlayProgress)
        overlayStatus = TextView(this).apply {
            text = "Connecting over Tor…\nThis can take 10–20 seconds."
            setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(14f)); gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        col.addView(overlayStatus)
        overlayRetry = Button(this).apply {
            text = "Retry"; visibility = View.GONE
            setOnClickListener { Log.i(TAG, "user tapped Retry"); recreate() }
        }
        col.addView(overlayRetry)
        col.addView(Button(this).apply {
            text = "Close"; setOnClickListener { finish() }
        })
        // tap anywhere on the overlay (while connecting) to reveal the call surface
        col.setOnClickListener { if (ecConnected) col.visibility = View.GONE }
        return col
    }

    /** widget -> SDK (fromWidget messages). */
    inner class Bridge(private val handle: org.matrix.rustcomponents.sdk.WidgetDriverHandle) {
        /** Called from the injected RTCPeerConnection patch with the focus onion that
         *  EC's WebRTC is about to use for TURN. This — not call membership — is the
         *  authoritative focus: it's whatever box's SFU/lk-jwt the call actually landed
         *  on. Point the TURN forwarder there so relay + SFU share one box. */
        @JavascriptInterface
        fun setTurnOnion(onion: String) {
            if (onion.isEmpty() || onion == turnOnion) return
            Log.i(TAG, "TURN focus onion (from EC ICE config) -> $onion")
            turnOnion = onion
            // Warm the circuit to the right coturn now, before WebRTC's allocation.
            runCatching { TorNet.prewarm(onion, 3478, TorManager.SOCKS_PORT) }
        }

        @JavascriptInterface
        fun fromWidget(json: String) {
            markConnected()   // EC loaded and is posting widget messages → hide overlay
            // Hang up / leave: Element Call posts `io.element.close`. Without this the
            // WebView lingers on a blank call surface (the "stuck on black screen"),
            // so close the activity and return to the chat.
            if (json.contains("\"io.element.close\"")) {
                Log.i(TAG, "EC requested close -> finishing call")
                runOnUiThread { if (!isFinishing) finish() }
                return
            }
            // Reverse the localhost rewrite: anything EC publishes (its call
            // membership / preferred focus) must carry the real onion so the peer's
            // box can resolve it — a bare 127.0.0.1 would point at the peer's own box.
            var out = json
            for ((a, b) in ecReverse) out = out.replace(a, b)
            if (BuildConfig.DEBUG && (out.contains("foci_preferred") || out.contains("focus_active")))
                Log.i(TAG, "FOCUS fromWidget(full): $out")
            if (BuildConfig.DEBUG) Log.i(TAG, "fromWidget: ${out.take(220)}")
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = runCatching { handle.send(out) }.getOrElse { Log.e(TAG, "send failed", it); false }
                if (!ok) Log.w(TAG, "handle.send returned false")
            }
        }
    }

    // "Other side hung up → end here too" detection from the m.call.member state.
    // Two traps make a naive check end LIVE calls (the "connection lost ~30s in" bug):
    //   (a) Our OWN membership has state_key "_<userId>_<deviceId>_m.call" — it never
    //       equals userId, so a self == me check misses it and counts US as a peer;
    //       when EC refreshes our membership (it briefly empties) it reads as a leave.
    //   (b) A previous call leaves a STALE non-empty membership for the peer until it
    //       expires; we'd see it "present" then "gone" and end a call they never joined.
    // So: skip our own membership (state_key CONTAINS our userId), treat everything
    // present in an initial grace window as baseline (stale / pre-existing, never arms a
    // leave), and only auto-end for a peer we actually watched JOIN this session — then
    // debounce to ride out membership-refresh flaps over slow Tor.
    private val presentPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val confirmedJoiners = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var ending = false
    private var peerLeftCheck: kotlinx.coroutines.Job? = null
    private val BASELINE_MS = 6_000L     // members present this soon after start = stale/baseline
    private val PEER_LEFT_DEBOUNCE_MS = 6_000L

    private fun maybeDetectPeerLeft(msg: String) {
        if (ending) return
        val me = MatrixRepo.userId
        runCatching {
            val state = org.json.JSONObject(msg).optJSONObject("data")?.optJSONArray("state") ?: return
            val nowPresent = HashSet<String>()
            var sawAnyMember = false
            for (i in 0 until state.length()) {
                val e = state.optJSONObject(i) ?: continue
                if (!e.optString("type").contains("call.member")) continue
                sawAnyMember = true
                val sk = e.optString("state_key")
                if (sk.isEmpty() || sk.contains(me)) continue   // skip our own per-device membership
                val content = e.optJSONObject("content")
                val present = when {
                    content == null -> false
                    content.has("memberships") -> (content.optJSONArray("memberships")?.length() ?: 0) > 0
                    else -> content.length() > 0          // empty {} ⇒ that member left
                }
                if (present) nowPresent.add(sk)
            }
            if (!sawAnyMember) return   // not a call-membership snapshot

            val elapsed = android.os.SystemClock.elapsedRealtime() - callStartMs
            if (elapsed < BASELINE_MS) {
                // Baseline: accumulate pre-existing/stale members; never arm a leave on them.
                presentPeers.addAll(nowPresent)
                return
            }
            // A peer present now but not a moment ago = a genuine join this session.
            for (sk in nowPresent) if (presentPeers.add(sk)) {
                confirmedJoiners.add(sk)
                Log.i(TAG, "peer joined the call: $sk")
            }
            presentPeers.retainAll(nowPresent)
            val anyConfirmedPresent = confirmedJoiners.any { presentPeers.contains(it) }
            if (confirmedJoiners.isNotEmpty() && !anyConfirmedPresent) {
                schedulePeerLeftEnd()
            } else {
                peerLeftCheck?.cancel(); peerLeftCheck = null
            }
        }
    }

    /** End the call only after the joined peer has stayed gone past the debounce window
     *  — a single empty membership snapshot is usually just a Tor-slowed refresh. */
    private fun schedulePeerLeftEnd() {
        if (ending || peerLeftCheck?.isActive == true) return
        peerLeftCheck = lifecycleScope.launch {
            kotlinx.coroutines.delay(PEER_LEFT_DEBOUNCE_MS)
            val anyBack = confirmedJoiners.any { presentPeers.contains(it) }
            if (!anyBack && !ending) {
                ending = true
                Log.i(TAG, "peer left the call (confirmed after debounce) -> ending on this side")
                runOnUiThread {
                    if (!isFinishing) {
                        android.widget.Toast.makeText(this@ElementCallActivity, "Call ended", android.widget.Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }
    }

    /** When the call's focus onion (the box hosting the SFU+lk-jwt) isn't ours — the
     *  cross-install join case — stand up bridges to it and register its rewrites so
     *  the WebView reaches it via 127.0.0.1 like it does our own box. */
    private fun maybeStartPeerFocus(msg: String) {
        for (m in focusRe.findAll(msg)) {
            val host = m.groupValues[1]
            if (host == ecOnion) continue
            if (!peerFocusStarted.add(host)) continue
            Log.i(TAG, "discovered peer focus onion $host -> starting peer bridges")
            runCatching {
                TorNet.startHttpProxy(JWT_PEER, "https://$host:8443", TorManager.HTTP_PORT, ecRewrites)
                TorNet.startTlsForwarder(SFU_PEER, host, 7443, TorManager.SOCKS_PORT)
            }.onFailure { Log.e(TAG, "peer bridge start failed", it) }
            // NB: we deliberately do NOT set turnOnion here. A foci_preferred in a
            // member's state is only a *candidate* focus, not the one this call landed
            // on — stale memberships would point the TURN relay at the wrong box. The
            // authoritative focus comes from the turn:<onion> EC actually requests,
            // reported via Bridge.setTurnOnion (the injected ICE patch).
            ecRewrites["https://$host:8443"] = "http://127.0.0.1:$JWT_PEER"
            ecRewrites["wss://$host:7443"] = "ws://127.0.0.1:$SFU_PEER"
            ecReverse["http://127.0.0.1:$JWT_PEER"] = "https://$host:8443"
            ecReverse["ws://127.0.0.1:$SFU_PEER"] = "wss://$host:7443"
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
                    // Serve the rtc_foci well-known INSTANTLY (no Tor round-trip). On a
                    // cold circuit the onion fetch takes >10s, but Element Call only
                    // waits ~5s before giving up and falling back to its PUBLIC clearnet
                    // SFU (livekit-jwt.call.element.io) — unreachable over Tor, so the
                    // call dies with "Something went wrong". We already know our box's
                    // focus, so answer locally and point it at our lk-jwt bridge.
                    if (request.url.encodedPath == "/.well-known/matrix/client") {
                        val wk = """{"m.homeserver":{"base_url":"http://127.0.0.1:$HS_LOCAL"},"org.matrix.msc4143.rtc_foci":[{"type":"livekit","livekit_service_url":"http://127.0.0.1:$JWT_LOCAL"}]}"""
                        val hdrs = linkedMapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Headers" to "*",
                        )
                        Log.i(TAG, "served synthetic well-known (rtc_foci -> 127.0.0.1:$JWT_LOCAL) instantly")
                        return android.webkit.WebResourceResponse(
                            "application/json", "utf-8", 200, "OK", hdrs,
                            java.io.ByteArrayInputStream(wk.toByteArray())
                        )
                    }
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
        connectTimeout?.cancel()
        peerLeftCheck?.cancel()
        Log.i(TAG, "call activity destroyed (connected=$ecConnected)")
        // Retract OUR RTC membership on leave. Element Call is meant to do this (incl.
        // via a delayed "leave" event), but over flaky Tor / an abrupt close it often
        // doesn't — and a lingering own m.call.member is poison: our app then reads
        // meInCall=true forever so the NEXT incoming call never rings, and the peer
        // sees a ghost participant that makes Element Call refuse new calls. Runs on
        // the repo's app-scope (survives this activity) over the SDK's own Tor path.
        callRoomId?.let { MatrixRepo.clearMyCallMembership(it) }
        runCatching { web.destroy() }
        // Tear down the per-call Tor bridges/forwarders so they don't linger.
        runCatching { TorNet.stopAll() }
    }
}
