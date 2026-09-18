package com.jarvistts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fetches a model file over HTTP instead of bundling it in the APK, so the
 *  installable APK stays small enough to hand to someone else. Downloads to
 *  a `.part` sibling and renames on success, so a killed/interrupted
 *  download can never look like a valid, ready-to-load model file.
 */
object ModelDownloader {
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val partFile = File(dest.parentFile, "${dest.name}.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} downloading $url")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!partFile.renameTo(dest)) {
            throw IOException("Failed to finalize download for ${dest.name}")
        }
    }
}
