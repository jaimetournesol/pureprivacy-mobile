package ai.tournesol.pureprivacy.tor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Embedded Tor — no Orbot. We exec the `libtor.so` PIE executable shipped by
 * info.guardianproject:tor-android with our own torrc, exposing a SOCKS port and
 * an HTTP-tunnel port on loopback for the matrix-rust-sdk client to use. This is
 * how PurePrivacy reaches the user's .onion box from a stock phone with no VPN.
 */
object TorManager {
    const val SOCKS_PORT = 9050
    const val HTTP_PORT = 8118
    private const val TAG = "PpTor"

    sealed class State {
        data object Idle : State()
        data class Bootstrapping(val percent: Int, val message: String) : State()
        data object Ready : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var process: Process? = null
    // Bumped on every start()/retry(). A start() invocation only writes _state while it
    // owns the current generation — so a stale loop tearing down after a retry() can't
    // clobber the fresh bootstrap's state with a Failed("tor exited").
    @Volatile private var generation = 0L

    /** Returns the SDK proxy URL once Tor is ready. */
    val proxyUrl: String get() = "socks5h://127.0.0.1:$SOCKS_PORT"

    suspend fun start(ctx: Context) = withContext(Dispatchers.IO) {
        if (process != null && _state.value is State.Ready) return@withContext
        val gen = ++generation
        fun setState(s: State) { if (gen == generation) _state.value = s }
        try {
            setState(State.Bootstrapping(0, "starting"))
            val torExe = findTorExecutable(ctx)
                ?: run { setState(State.Failed("libtor.so not found in nativeLibraryDir")); return@withContext }

            val dataDir = File(ctx.filesDir, "tor").apply { mkdirs() }
            val torrc = File(dataDir, "torrc")
            torrc.writeText(
                """
                DataDirectory ${dataDir.absolutePath}
                SocksPort 127.0.0.1:$SOCKS_PORT
                HTTPTunnelPort 127.0.0.1:$HTTP_PORT
                ClientOnly 1
                AvoidDiskWrites 1
                CookieAuthentication 0
                ControlPort 0
                SafeLogging 0
                Log notice stdout
                """.trimIndent()
            )

            Log.i(TAG, "exec ${torExe.absolutePath} -f ${torrc.absolutePath}")
            val pb = ProcessBuilder(torExe.absolutePath, "-f", torrc.absolutePath)
                .redirectErrorStream(true)
                .directory(dataDir)
            pb.environment()["HOME"] = dataDir.absolutePath
            val proc = pb.start()
            process = proc

            // Parse tor's notice log for bootstrap progress.
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, line)
                    val m = Regex("""Bootstrapped (\d+)%[^:]*:?\s*(.*)""").find(line)
                    if (m != null) {
                        val pct = m.groupValues[1].toIntOrNull() ?: 0
                        val msg = m.groupValues[2].ifBlank { "bootstrapping" }
                        if (pct >= 100) {
                            setState(State.Ready)
                            Log.i(TAG, "Tor ready (100%)")
                        } else {
                            setState(State.Bootstrapping(pct, msg))
                        }
                    }
                    if (line.contains("[err]") || line.contains("Reading config failed")) {
                        setState(State.Failed(line))
                    }
                }
            }
            val code = proc.waitFor()
            // Only fault if WE'RE still the live generation and didn't reach Ready — a
            // retry() that killed this process has already bumped the generation, so
            // setState here is a no-op and won't stomp the new bootstrap.
            if (gen == generation && _state.value !is State.Ready) setState(State.Failed("tor exited ($code)"))
        } catch (t: Throwable) {
            Log.e(TAG, "tor start failed", t)
            setState(State.Failed(t.message ?: t.toString()))
        }
    }

    private fun findTorExecutable(ctx: Context): File? {
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        // tor-android ships the executable as libtor.so
        listOf("libtor.so", "tor", "libTor.so").forEach {
            val f = File(libDir, it)
            if (f.exists()) return f
        }
        return libDir.listFiles()?.firstOrNull { it.name.contains("tor", ignoreCase = true) }
    }

    /** User-driven retry from the UI when Tor is stuck/failed (the status badge or a
     *  login/splash error path). Tears down any current process and starts a fresh
     *  bootstrap. Safe to call repeatedly: a Ready Tor is left alone; otherwise we
     *  kill the old `tor` exec (whose log-reader loop in start() then unblocks and
     *  returns) and re-run start(), which resets state to Bootstrapping(0). */
    suspend fun retry(ctx: Context) = withContext(Dispatchers.IO) {
        if (_state.value is State.Ready && process != null) return@withContext
        Log.i(TAG, "Tor retry requested — restarting")
        runCatching { process?.destroy() }
        process = null
        _state.value = State.Bootstrapping(0, "retrying")
        start(ctx)
    }

    fun stop() {
        process?.destroy()
        process = null
        _state.value = State.Idle
    }
}
