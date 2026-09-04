package ai.champi.context

import android.content.Context
import java.io.File
import java.io.InputStream

/** Cache sub-directory name under [Context.cacheDir] used for share-sheet attachments. */
internal const val SHARE_CACHE_DIR = "share_attachments"

/** Maximum total size in bytes for the share-attachment cache before old files are evicted. */
private const val MAX_CACHE_BYTES = 50L * 1024 * 1024 // 50 MB

/**
 * Writes [inputStream] into a new file under the app's share-attachment cache directory and
 * returns the absolute path of the written file. The file name is derived from [originalName]
 * with a timestamp prefix so repeated shares of same-named files never collide.
 *
 * Callers must close [inputStream] after this function returns.
 */
internal fun writeToCacheAndEvict(
    context: Context,
    inputStream: InputStream,
    originalName: String,
): String {
    val dir = File(context.cacheDir, SHARE_CACHE_DIR).also { it.mkdirs() }
    val target = File(dir, "${System.currentTimeMillis()}_$originalName")
    target.outputStream().use { out -> inputStream.copyTo(out) }
    evictIfNeeded(dir)
    return target.absolutePath
}

/**
 * Deletes the oldest files in [dir] (by last-modified time) until the total size is below
 * [MAX_CACHE_BYTES]. Called immediately after every write so the directory stays bounded.
 */
internal fun evictIfNeeded(dir: File) {
    val files = dir.listFiles() ?: return
    var totalBytes = files.sumOf { it.length() }
    if (totalBytes <= MAX_CACHE_BYTES) return

    val sorted = files.sortedBy { it.lastModified() }
    for (file in sorted) {
        if (totalBytes <= MAX_CACHE_BYTES) break
        totalBytes -= file.length()
        file.delete()
    }
}

/**
 * Deletes all files under the share-attachment cache directory. Intended to be called at
 * application startup so any leftover files from a previous session are cleaned up eagerly.
 */
fun clearShareCache(context: Context) {
    File(context.cacheDir, SHARE_CACHE_DIR).listFiles()?.forEach { it.delete() }
}
