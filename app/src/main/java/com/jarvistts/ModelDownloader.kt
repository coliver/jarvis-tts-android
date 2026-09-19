package com.jarvistts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Fetches a model file over HTTP instead of bundling it in the APK, so the
 *  installable APK stays small enough to hand to someone else. Downloads to
 *  a `.part` sibling and renames on success, so a killed/interrupted
 *  download can never look like a valid, ready-to-load model file. The
 *  `.part` file is hashed as it's written and checked against
 *  [expectedSha256] before the rename, so a *completed-but-corrupt* download
 *  (bit flip, a proxy that truncates but still returns 200) can't look valid
 *  either -- the rename-on-success guard alone only caught killed downloads.
 */
object ModelDownloader {
    suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val partFile = File(dest.parentFile, "${dest.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
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
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actualSha256 = digest.digest().toHexString()
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            partFile.delete()
            throw IOException(
                "Checksum mismatch downloading ${dest.name}: expected $expectedSha256, got $actualSha256",
            )
        }
        if (!partFile.renameTo(dest)) {
            throw IOException("Failed to finalize download for ${dest.name}")
        }
    }

    /** Split out from [download] so hex-encoding is testable under plain
     *  JUnit without exercising the network path.
     */
    fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
