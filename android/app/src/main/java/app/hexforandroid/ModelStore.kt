package app.hexforandroid

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * GigaAM v3 e2e RNN-T, the Russian model Hex uses on the desktop
 * (`GIGAAM_V3_E2E_RNNT_ARTIFACT` in src/transcription/artifacts.rs), pinned to
 * the same revision and hash. Q5_K_M keeps the download at 197 MB and runs
 * comfortably on a phone's CPU.
 *
 * The file lives in the app's external files directory so a development
 * machine can also `adb push` it there instead of downloading.
 */
object ModelStore {
    const val FILENAME = "gigaam-v3-e2e-rnnt-Q5_K_M.gguf"
    private const val REPOSITORY = "handy-computer/gigaam-v3-e2e-rnnt-gguf"
    private const val REVISION = "3efd5b39882ceadca5570a8fb9fa1a2c801d6d6d"
    const val BYTES = 206_392_736L
    private const val SHA256 = "0f5969b05f7e196e8922e243c64963132025a5ae7ec2f83d028ae120d201594f"
    private const val URL_STRING = "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$FILENAME"

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun file(context: Context) = File(dir(context), FILENAME)

    private fun marker(context: Context) = File(dir(context), "$FILENAME.verified")

    /** True when the file is present and its hash was checked for this size and time. */
    fun isVerified(context: Context): Boolean {
        val model = file(context)
        if (!model.isFile || model.length() != BYTES) return false
        val marker = marker(context)
        return marker.isFile && marker.readText() == stamp(model)
    }

    fun isPresent(context: Context) = file(context).let { it.isFile && it.length() == BYTES }

    private fun stamp(model: File) = "${model.length()}:${model.lastModified()}:$SHA256"

    /** Hashes the file (a few seconds) and records the result. */
    fun verify(context: Context, progress: (Float) -> Unit = {}): Boolean {
        val model = file(context)
        if (!model.isFile || model.length() != BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        var read = 0L
        FileInputStream(model).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                read += n
                progress(read.toFloat() / BYTES)
            }
        }
        val ok = digest.digest().joinToString("") { "%02x".format(it) } == SHA256
        if (ok) marker(context).writeText(stamp(model)) else marker(context).delete()
        return ok
    }

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Downloads with resume from a partial file, then verifies. Blocking; one at a time. */
    fun download(context: Context, progress: (Long, Long) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) throw IllegalStateException("A download is already running")
        try {
            return downloadOnce(context, progress)
        } finally {
            busy.set(false)
        }
    }

    private fun downloadOnce(context: Context, progress: (Long, Long) -> Unit): Boolean {
        val part = File(dir(context), "$FILENAME.part")
        var have = if (part.isFile) part.length() else 0L
        if (have > BYTES) {
            part.delete()
            have = 0
        }
        if (have < BYTES) {
            val connection = (URL(URL_STRING).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = connection.responseCode
            if (code == 200 && have > 0) have = 0 // The server ignored the range.
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code from Hugging Face")
            connection.inputStream.use { input ->
                FileOutputStream(part, have > 0).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        have += n
                        if (have - last > (1 shl 20)) {
                            progress(have, BYTES)
                            last = have
                        }
                    }
                }
            }
        }
        // A stream that ended early keeps its partial file for the next resume.
        if (have != BYTES) throw IllegalStateException("The download stopped at ${have shr 20} MB; try again to resume")
        progress(BYTES, BYTES)
        val model = file(context)
        model.delete()
        if (!part.renameTo(model)) throw IllegalStateException("could not move the download into place")
        return verify(context)
    }
}
