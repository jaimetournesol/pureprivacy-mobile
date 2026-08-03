package ai.tournesol.pureprivacy

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import ai.tournesol.pureprivacy.matrix.MatrixRepo
import ai.tournesol.pureprivacy.net.TorNet
import ai.tournesol.pureprivacy.tor.TorManager
import java.io.ByteArrayInputStream

/**
 * Agent settings — the Hermes WebUI, on your box, over Tor.
 *
 * This is the agents' control plane: create and configure agents, pick models, watch runs.
 * It is deliberately NOT part of the Agents app's chat surface — talking to an agent and
 * administering one are different acts, and only one of them can change what the agent is
 * allowed to do.
 *
 * Three things make it safe to expose at all:
 *
 *  1. **Its own hidden service.** The WebUI can run shell commands. The box's main onion is
 *     known to every paired peer box, so this rides a SECOND onion (`hs-agent`, port 8788)
 *     that nothing else is published on. Its address reaches only the owner's phone, via the
 *     agent registry in the owner's own account data.
 *  2. **A password.** Generated in the container, handed to us by the box, filled in below.
 *     Without it the WebUI serves its API to anyone who reaches the port.
 *  3. **No clearnet, ever.** The upstream UI pulls KaTeX/Mermaid/Prism from a CDN. On a
 *     phone with normal internet, a WebView would happily fetch those *outside* Tor — which
 *     would tell a CDN, and anyone watching the phone's traffic, that this device
 *     administers a Hermes box. So every non-loopback request is refused (see below). Math
 *     and diagram rendering degrade; the leak doesn't happen.
 */
class AgentSettingsActivity : ComponentActivity() {
    private val TAG = "PpAgentUI"
    private lateinit var web: WebView
    private var status: TextView? = null

    companion object {
        /** Loopback port for the bridge to the agent WebUI's onion. Distinct from the call
         *  bridges in [ElementCallActivity] so the two can be up at once. */
        const val AGENT_LOCAL = 18787

        /**
         * Password to use instead of the one in the registry.
         *
         * Set when we arrive straight from a password change: the box republishes the roster
         * on its own schedule and the phone then has to sync it, so for a little while the
         * registry still holds the OLD password — and auto-filling that would greet the owner
         * with "Invalid password" for a password they just successfully set. We already know
         * the right one; use it.
         */
        const val EXTRA_PASSWORD = "pp_webui_password"
    }

    /** The WebUI is restarted by the box when the password changes, so arriving right after a
     *  change can land mid-restart. Retry a few times before calling it a failure. */
    private var loadAttempts = 0
    private val maxLoadAttempts = 6

    /**
     * Pending `<input type="file">` callback for the WebUI's attach button.
     *
     * A WebView does NOT open a file chooser on its own: without a [android.webkit.WebChromeClient]
     * that handles `onShowFileChooser`, tapping a file input does nothing at all — no picker, no
     * error, no log. That is exactly what attach did here. The callback must be answered exactly
     * once (with the URIs, or `null` if the user backs out), or the input stays wedged and every
     * later tap is ignored too.
     */
    private var pendingFileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    private val fileChooser = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.onReceiveValue(
            android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The agents' control plane is exactly the kind of screen that shouldn't end up in
        // a screen recording or the recents thumbnail.
        applyScreenSecurity()

        val webui = MatrixRepo.agentWebui.value
        if (webui == null || webui.onion.isBlank()) {
            // Not an error state so much as "the box hasn't told us yet" — the roster is
            // published by the box and arrives with sync.
            Log.w(TAG, "no agent WebUI published yet — finishing")
            android.widget.Toast.makeText(
                this, "Your box hasn't published the agent settings address yet.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            finish(); return
        }
        // A password handed to us by the screen that just set it beats the registry's copy,
        // which may not have caught up yet.
        val password = intent.getStringExtra(EXTRA_PASSWORD)?.takeIf { it.isNotBlank() }
            ?: webui.password
        Log.i(TAG, "agent settings: onion=${webui.onion.take(10)}… port=${webui.port} tor=${TorManager.state.value}")

        // Tunnel: plain TCP to the agent onion. Raw TCP (not the HTTP-CONNECT tunnel),
        // because that tunnel is TLS-only and this hop is plain HTTP inside the onion's
        // own encryption — the transport is already end-to-end to the box.
        TorNet.startTcpForwarder(AGENT_LOCAL, { webui.onion }, webui.port, TorManager.SOCKS_PORT)

        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(buildOverlay())
        setContentView(root)
        insetForSystemBars(root)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The WebUI streams over WebSocket to the same origin; both ride the bridge.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // Attach. The upload itself never leaves the onion: the file is read by the WebView and
        // POSTed to 127.0.0.1, which is the Tor bridge to the box. The clearnet guard below is
        // untouched — a picked file cannot become an off-Tor request.
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(
                v: WebView?,
                callback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                // A second chooser would orphan the first callback; answer it before replacing.
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = callback
                return try {
                    // The picker is DocumentsUI — a different process, so the app "backgrounds"
                    // and the passcode auto-lock would fire and tear down this activity before
                    // the file arrived. Same exemption the in-app pickers take.
                    AppViewModel.beginExternalPick()
                    fileChooser.launch(params!!.createIntent())
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "no file picker available: ${e.message}")
                    pendingFileCallback = null
                    callback?.onReceiveValue(null)
                    false
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            /**
             * The clearnet guard. Anything that isn't our loopback bridge is refused
             * outright rather than fetched off-Tor. This is the whole reason the WebView
             * can be pointed at an upstream UI we don't control: a CDN reference added
             * upstream tomorrow still cannot become a request from this phone.
             */
            override fun shouldInterceptRequest(
                v: WebView?, req: WebResourceRequest?,
            ): WebResourceResponse? {
                val host = req?.url?.host ?: return null
                if (host == "127.0.0.1" || host == "localhost") return null
                Log.i(TAG, "blocked off-Tor request to $host")
                return WebResourceResponse(
                    "text/plain", "utf-8", 403, "Blocked",
                    emptyMap(), ByteArrayInputStream(ByteArray(0)),
                )
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                status?.let { it.visibility = View.GONE }
                overlay?.visibility = View.GONE
                if (url != null && url.contains("/login")) fillPassword(password)
            }

            override fun onReceivedError(
                v: WebView?, req: WebResourceRequest?, err: android.webkit.WebResourceError?,
            ) {
                // Only the main document matters — a blocked CDN sub-resource is expected.
                if (req?.isForMainFrame != true) return
                retryOrGiveUp()
            }
        }

        web.loadUrl("http://127.0.0.1:$AGENT_LOCAL/")
    }

    /**
     * Keep the WebUI out from under the status bar and the navigation bar.
     *
     * targetSdk 35+ makes every window edge-to-edge, and this activity is a bare WebView in a
     * FrameLayout — nothing insets it. The Hermes WebUI puts its composer toolbar flush with
     * the bottom of the viewport, so the system nav bar landed directly on top of it: the
     * three-button bar covered the row, and the leftmost control (attach) sat *under* the
     * back/home/recents strip, so tapping it hit the nav bar instead of the button. The bug
     * reads as two separate faults ("the buttons overlap" and "attach doesn't work") but it
     * is one.
     *
     * Padding the root rather than the WebView (or injecting CSS) keeps this independent of
     * the upstream UI's markup, which we don't control. `ime()` is unioned in so the composer
     * rides above the keyboard instead of behind it — in edge-to-edge nothing resizes for the
     * IME on our behalf. The root is painted in the WebUI's own chrome colour so the inset
     * bands read as part of the page rather than as letterboxing.
     */
    private fun insetForSystemBars(root: View) {
        root.setBackgroundColor(0xFF141425.toInt())   // Hermes WebUI chrome
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Retry the load, or explain the failure.
     *
     * Arriving straight from setup is the common case now, and setup ends with the box
     * restarting the WebUI on the new password — so the first attempt genuinely can hit a
     * server that isn't listening yet. Silently spinning forever would look identical to a
     * broken box, so we bound the retries and then say what happened.
     */
    private fun retryOrGiveUp() {
        loadAttempts++
        if (loadAttempts >= maxLoadAttempts) {
            status?.text = "Couldn't reach your agents' settings.\n" +
                "Your box may still be starting them — try again in a moment."
            overlayProgress?.visibility = View.GONE
            overlay?.visibility = View.VISIBLE
            return
        }
        status?.text = "Your box is still starting the agent settings…"
        overlay?.visibility = View.VISIBLE
        overlayProgress?.visibility = View.VISIBLE
        web.postDelayed({ web.loadUrl("http://127.0.0.1:$AGENT_LOCAL/") }, 5_000)
    }

    private var overlay: LinearLayout? = null
    private var overlayProgress: ProgressBar? = null

    /** "Connecting over Tor…" cover — the first load builds a circuit, which is seconds of
     *  otherwise-blank white. */
    private fun buildOverlay(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF16140F.toInt())   // Ink, as in the Compose theme
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        box.addView(ProgressBar(this).also { overlayProgress = it })
        box.addView(TextView(this).apply {
            text = "Connecting to your box over Tor…"
            setTextColor(0xFFECE6D7.toInt())         // Paper
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
            status = this
        })
        overlay = box
        return box
    }

    /**
     * Fill in the WebUI password.
     *
     * The owner never chose this password — the container generated it — so asking them to
     * type it would be asking for something they don't have. We put it in the field and
     * submit. It is still a real gate: without the box's roster (which needs the owner's
     * Matrix account, over the onion) nobody else has it.
     */
    private fun fillPassword(password: String) {
        if (password.isBlank()) {
            Log.w(TAG, "no WebUI password published — the owner must type one")
            return
        }
        val js = """
            (function(){
              var f = document.getElementById('login-form');
              var p = document.getElementById('pw');
              if (!f || !p || p.dataset.ppFilled) return;
              p.dataset.ppFilled = '1';
              p.value = ${org.json.JSONObject.quote(password)};
              p.dispatchEvent(new Event('input', {bubbles:true}));
              if (f.requestSubmit) f.requestSubmit(); else f.submit();
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        // Leave the Tor forwarder alone: TorNet keys listeners by port and reuses them, and
        // a call may be running on its own bridges. It's torn down with the rest by
        // TorNet.stopAll() on sign-out.
        // Never leave a file-input callback unanswered — Chromium holds the native side open.
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        runCatching { web.destroy() }
        super.onDestroy()
    }
}
