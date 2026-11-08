package app.clustercontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.clustercontrol.core.Release
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads a release apk into the cache and hands it to the system installer (which also checks the signature). */
class Updater(private val ctx: Context) {
    /** Only the apk of [release]; a checksum mismatch deletes it. [onProgress] gets 0..1. */
    fun download(release: Release, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "ClusterControl-${release.version}.apk")
        val c = URL(release.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
            release.sha256?.let { want ->
                check(md.digest().joinToString("") { "%02x".format(it) } == want) { "checksum mismatch" }
            }
            return out
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** false when the system first needs the owner to allow installs from this app (its settings page opens). */
    fun install(apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
