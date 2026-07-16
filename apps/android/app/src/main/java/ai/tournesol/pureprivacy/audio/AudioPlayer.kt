package ai.tournesol.pureprivacy.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * A single-clip player for received voice notes. Writes the decrypted OGG bytes to a
 * cache file and plays them with [MediaPlayer]. Only one clip plays at a time — starting a
 * new one (or replaying the current) stops whatever was playing. [onDone] fires when the
 * clip finishes or is stopped, so the UI can drop its "playing" highlight.
 */
object AudioPlayer {
    private const val TAG = "PpVoice"
    private var player: MediaPlayer? = null
    private var playingKey: String? = null

    /** The key of the clip currently playing, or null. Lets a bubble show a pause icon. */
    val currentKey: String? get() = playingKey

    /** Play [bytes] for message [key]. If that key is already playing, stop instead
     *  (tap-to-toggle). [onDone] is invoked on completion/stop (on the main thread). */
    @Synchronized
    fun toggle(ctx: Context, key: String, bytes: ByteArray, onDone: () -> Unit) {
        if (playingKey == key) { stop(); onDone(); return }
        stop()
        val f = File(ctx.cacheDir, "pp_voice_play.ogg")
        runCatching { f.writeBytes(bytes) }.onFailure { Log.e(TAG, "write clip failed", it); return }
        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { stop(); onDone() }
            mp.prepare(); mp.start()
        }.onFailure {
            Log.e(TAG, "play failed", it); runCatching { mp.release() }; onDone(); return
        }
        player = mp; playingKey = key
    }

    @Synchronized
    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null; playingKey = null
    }
}
