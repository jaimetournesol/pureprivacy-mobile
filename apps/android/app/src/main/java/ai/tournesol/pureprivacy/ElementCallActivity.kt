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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val room = MatrixRepo.currentRoom
        if (room == null) { finish(); return }

        // 1) Route ALL WebView traffic through embedded Tor. SOCKS5 so Tor does the
        //    DNS — required for .onion (Chromium won't resolve .onion locally, and an
        //    HTTP proxy makes it resolve client-side). Fall back to the HTTP tunnel.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val cfg = ProxyConfig.Builder()
                .addProxyRule("socks5://127.0.0.1:${TorManager.SOCKS_PORT}")
                .addProxyRule("127.0.0.1:${TorManager.HTTP_PORT}")
                .build()
            ProxyController.getInstance().setProxyOverride(cfg, { it.run() }, {})
        }

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
                    elementCallUrl = "https://call.element.io",
                    widgetId = "pp-call",
                    parentUrl = null,
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
                val url = generateWebviewUrl(
                    settings, room,
                    ClientProperties("ai.tournesol.pureprivacy", null, "dark")
                )
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
                            web.evaluateJavascript("window.postMessage($msg, '*');", null)
                        }
                    }
                    Log.w(TAG, "recv loop ended")
                }

                withContext(Dispatchers.Main) {
                    web.addJavascriptInterface(Bridge(handle), "ppAndroid")
                    web.webViewClientInjectOnLoad()
                    web.loadUrl(url)
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
            override fun onPageFinished(view: WebView, url: String?) {
                // forward fromWidget messages to Android (filter to avoid echoing toWidget)
                view.evaluateJavascript(
                    """
                    (function(){
                      if (window.__ppHooked) return; window.__ppHooked = true;
                      window.addEventListener('message', function(e){
                        try {
                          var d = e.data; if (!d || !d.api) return;
                          // Only forward genuine widget -> client traffic to the SDK:
                          //  - fromWidget REQUESTS (no 'response' yet)
                          //  - toWidget RESPONSES (widget answering a client request)
                          // NEVER re-forward the messages we inject (fromWidget+response,
                          // toWidget request) — that caused an echo loop.
                          var isReq  = (d.api === 'fromWidget' && !('response' in d));
                          var isResp = (d.api === 'toWidget'   &&  ('response' in d));
                          if (isReq || isResp) { ppAndroid.fromWidget(JSON.stringify(d)); }
                        } catch(err){}
                      });
                    })();
                    """.trimIndent(), null
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { web.destroy() }
    }
}
