package com.jarvistts

/** A saved conversation: the turn history plus enough metadata to list and
 *  resume it later. Persisted as JSON by [SessionStore].
 */
data class ChatSession(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val turns: List<Turn>,
)

/** Lightweight listing entry, avoids loading every session's full turn
 *  history just to render the history picker.
 */
data class SessionSummary(val id: String, val title: String, val updatedAt: Long)

/** JSON encode/decode for [ChatSession], built on [MiniJson] so this stays
 *  dependency-free and testable under plain JUnit.
 */
object SessionCodec {
    fun encode(session: ChatSession): String =
        MiniJson.stringify(
            mapOf(
                "id" to session.id,
                "createdAt" to session.createdAt,
                "updatedAt" to session.updatedAt,
                "title" to session.title,
                "turns" to
                    session.turns.map { turn ->
                        mapOf(
                            "speaker" to turn.speaker.name,
                            "text" to turn.text,
                            "durationMs" to turn.durationMs,
                        )
                    },
            ),
        )

    fun decode(json: String): ChatSession {
        @Suppress("UNCHECKED_CAST")
        val obj = MiniJson.parse(json) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val turnsRaw = obj["turns"] as List<Map<String, Any?>>
        val turns =
            turnsRaw.map { t ->
                Turn(
                    speaker = Speaker.valueOf(t["speaker"] as String),
                    text = t["text"] as String,
                    durationMs = (t["durationMs"] as Number).toLong(),
                )
            }
        return ChatSession(
            id = obj["id"] as String,
            createdAt = (obj["createdAt"] as Number).toLong(),
            updatedAt = (obj["updatedAt"] as Number).toLong(),
            title = obj["title"] as String,
            turns = turns,
        )
    }
}
