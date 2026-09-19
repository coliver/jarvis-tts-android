package com.jarvistts

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Wikipedia lookup for spoken "search for X" requests. No API key needed. Returns the
 *  article intro's first sentences verbatim rather than passing them through the LLM:
 *  the intro already reads as an answer, and a 1B model would add latency and risk.
 */
object WebSearch {
    private const val NOT_FOUND = "I could not find anything on that."

    fun search(query: String): String =
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url =
                "https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$q&gsrlimit=1" +
                    "&prop=extracts&exintro=1&explaintext=1&format=json"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "JarvisTTS/1.0 (Android voice assistant)")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            parseExtract(body)?.let { firstSentences(it, 2) } ?: NOT_FOUND
        } catch (e: Exception) {
            "I could not reach the web right now."
        }

    fun parseExtract(json: String): String? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val pages = (root["query"] as? Map<*, *>)?.get("pages") as? Map<*, *> ?: return null
        val page = pages.values.firstOrNull() as? Map<*, *> ?: return null
        return (page["extract"] as? String)?.takeIf { it.isNotBlank() }
    }

    fun firstSentences(
        text: String,
        count: Int,
    ): String {
        val cleaned = text.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
        val sentences = Regex("(?<=[.!?])\\s+(?=[A-Z])").split(cleaned)
        return sentences.take(count).joinToString(" ")
    }
}
