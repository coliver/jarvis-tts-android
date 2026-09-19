package com.jarvistts

data class ToolCall(val name: String, val args: Map<String, String>)

/** Deterministic intent routing for device tools. A 1B model can't be trusted to emit
 *  tool-call JSON (observed: it ignores the format and invents an answer), so the
 *  utterance is matched here before the LLM is ever involved. Anything that doesn't
 *  match goes to the LLM as an ordinary question.
 */
object Tools {
    private val TIME =
        Regex(
            "\\b(what('s| is)?( the)? (time|date|day)|time is it|current (time|date)|tell me the (time|date)|today's date)\\b",
            RegexOption.IGNORE_CASE,
        )
    private val BATTERY = Regex("\\bbattery\\b", RegexOption.IGNORE_CASE)
    private val TIMER = Regex("\\btimer\\b.*?\\b(?:for|of)\\s+(.+)", RegexOption.IGNORE_CASE)
    private val EMAIL_WITH_BODY =
        Regex(
            "\\be-?mail\\s+(?:to\\s+)?(.+?)\\s+(?:saying|that says|and say|telling (?:him|her|them)|about|with the message)\\s+(.+)",
            RegexOption.IGNORE_CASE,
        )
    private val SEARCH =
        Regex("\\b(?:search(?: the web| wikipedia)?(?: for)?|look up|wikipedia|who is|who was|who are)\\s+(.+)", RegexOption.IGNORE_CASE)
    private val EMAIL_ADDRESS_ONLY = Regex("\\be-?mail\\s+(?:to\\s+)?(.+)", RegexOption.IGNORE_CASE)
    private val DURATION =
        Regex(
            "(\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|forty-five|sixty)" +
                "\\s*(second|minute|hour)s?",
            RegexOption.IGNORE_CASE,
        )

    private val WORD_NUMBERS =
        mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15,
            "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45, "sixty" to 60,
        )

    fun route(utterance: String): ToolCall? {
        val text = utterance.trim()
        if (text.isEmpty()) return null
        EMAIL_WITH_BODY.find(text)?.let {
            return ToolCall("send_email", mapOf("to" to it.groupValues[1], "body" to it.groupValues[2].trim()))
        }
        if (Regex("\\be-?mail\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            Regex("\\b(send|write|draft|compose)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) {
            EMAIL_ADDRESS_ONLY.find(text)?.let { return ToolCall("send_email", mapOf("to" to it.groupValues[1])) }
        }
        TIMER.find(text)?.let {
            val secs = parseDurationSeconds(it.groupValues[1])
            if (secs != null) return ToolCall("set_timer", mapOf("seconds" to secs.toString()))
        }
        SEARCH.find(text)?.let {
            val query = it.groupValues[1].trim().trimEnd('.', '?', '!', ',')
            if (query.isNotEmpty()) return ToolCall("web_search", mapOf("query" to query))
        }
        if (BATTERY.containsMatchIn(text)) return ToolCall("get_battery", emptyMap())
        if (TIME.containsMatchIn(text)) return ToolCall("get_time", emptyMap())
        return null
    }

    fun parseDurationSeconds(text: String): Int? {
        var total = 0
        var found = false
        for (m in DURATION.findAll(text)) {
            val n = m.groupValues[1].lowercase().let { it.toIntOrNull() ?: WORD_NUMBERS[it] } ?: continue
            total +=
                n *
                when (m.groupValues[2].lowercase()) {
                    "hour" -> 3600
                    "minute" -> 60
                    else -> 1
                }
            found = true
        }
        return if (found && total > 0) total else null
    }

    /** Whisper transcribes a spoken address as words ("chris at gmail dot com"). */
    fun normalizeSpokenEmail(raw: String): String =
        raw
            .trim()
            .trimEnd('.', ',', '?', '!')
            .replace(Regex("\\s+at\\s+", RegexOption.IGNORE_CASE), "@")
            .replace(Regex("\\s+dot\\s+", RegexOption.IGNORE_CASE), ".")
            .replace(" ", "")

    fun isPlausibleEmail(address: String): Boolean = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(address)

    fun describeDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            "$h hour${if (h == 1) "" else "s"}".takeIf { h > 0 },
            "$m minute${if (m == 1) "" else "s"}".takeIf { m > 0 },
            "$s second${if (s == 1) "" else "s"}".takeIf { s > 0 },
        ).joinToString(" and ")
    }
}
