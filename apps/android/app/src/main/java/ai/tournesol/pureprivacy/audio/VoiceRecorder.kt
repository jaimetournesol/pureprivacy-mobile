package ai.tournesol.pureprivacy.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Records a short voice note as **OGG/Opus** — the codec Matrix voice messages use — and
 * derives a coarse amplitude waveform by polling [MediaRecorder.getMaxAmplitude] while
 * recording, so we never have to decode PCM. One recorder at a time.
 *
 * Voice notes need **Android 10+**: OGG output + the Opus encoder on [MediaRecorder]
 * only exist from API 29. On older devices [supported] is false and the mic button is
 * hidden rather than producing an unplayable format.
 */
class VoiceRecorder(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var startMs = 0L
    private val amps = ArrayList<Int>()
    @Volatile private var polling = false

    val isRecording: Boolean get() = recorder != null
    val elapsedMs: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startMs else 0L

    fun supported(): Boolean = Build.VERSION.SDK_INT >= 29

    /** Begin recording. Returns false if unsupported, already recording, or the mic
     *  couldn't be opened (e.g. permission denied, device busy). */
    fun start(): Boolean {
        if (!supported() || recorder != null) return false
        val dir = File(ctx.cacheDir, "pp_voice").apply { mkdirs() }
        val f = File(dir, "voice_${SystemClock.elapsedRealtime()}.ogg")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioEncodingBitRate(24_000)   // plenty for voice; keeps the clip small over Tor
            r.setAudioSamplingRate(48_000)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
        }.map {
            recorder = r; outFile = f; startMs = SystemClock.elapsedRealtime()
            amps.clear(); polling = true
            // Poll peak amplitude on a daemon thread → a real (if coarse) waveform, no PCM.
            Thread {
                while (polling) {
                    runCatching { amps.add(recorder?.maxAmplitude ?: 0) }
                    try { Thread.sleep(80) } catch (_: InterruptedException) { break }
                }
            }.apply { isDaemon = true }.start()
            true
        }.getOrElse { Log.e(TAG, "start failed", it); runCatching { r.release() }; false }
    }

    /** Stop and return (oggPath, durationMs, waveform normalised 0..1). Null if it failed
     *  or is shorter than [MIN_MS] (a stray tap, not a note) — the file is deleted then. */
    fun stop(): Triple<String, Long, List<Float>>? {
        val r = recorder ?: return null
        polling = false
        val dur = SystemClock.elapsedRealtime() - startMs
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        recorder = null
        val f = outFile; outFile = null
        if (!ok || f == null || !f.exists() || f.length() == 0L || dur < MIN_MS) {
            runCatching { f?.delete() }; return null
        }
        val peak = (amps.maxOrNull() ?: 1).coerceAtLeast(1)
        val norm = amps.map { (it.toFloat() / peak).coerceIn(0f, 1f) }
        return Triple(f.absolutePath, dur, downsample(norm, WAVE_BUCKETS))
    }

    /** Abandon the current recording (user cancelled) — release the mic + delete, no send. */
    fun cancel() {
        polling = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { outFile?.delete() }
        outFile = null
    }

    /** Bucket a dense amplitude list down to [n] bars, taking each bucket's peak. */
    private fun downsample(xs: List<Float>, n: Int): List<Float> {
        if (xs.isEmpty()) return List(n) { 0f }
        if (xs.size <= n) return xs
        val bucket = xs.size.toFloat() / n
        return (0 until n).map { i ->
            val from = (i * bucket).toInt()
            val to = ((i + 1) * bucket).toInt().coerceAtMost(xs.size)
            if (to <= from) xs[from] else xs.subList(from, to).max()
        }
    }

    private companion object {
        const val TAG = "PpVoice"
        const val MIN_MS = 700L
        const val WAVE_BUCKETS = 48
    }
}
