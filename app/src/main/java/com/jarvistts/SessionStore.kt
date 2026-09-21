package com.jarvistts

import java.io.File
import java.util.UUID

private const val TITLE_MAX_LENGTH = 48

/** Filesystem-backed CRUD for saved conversations. Each session is one JSON
 *  file under [baseDir], named "<id>.json", so list/delete are just
 *  directory operations. Takes a plain [File] instead of a Context so this
 *  stays testable under plain JUnit, no Robolectric/instrumented tests
 *  (deliberately avoided in this repo, see AGENTS.md).
 */
class SessionStore(private val baseDir: File) {
    companion object {
        /** Cap on saved sessions; [create] deletes the oldest-updated ones
         *  beyond this so history doesn't grow without bound (AGENTS.md
         *  backlog). [update] never adds a file, so it doesn't prune.
         */
        const val MAX_SESSIONS = 200
    }

    private fun fileFor(id: String) = File(baseDir, "$id.json")

    /** Creates and persists a new session from an initial turn list. Turns
     *  must be non-empty, an empty session is meaningless and would clutter
     *  the history list with nothing to show or resume.
     */
    fun create(turns: List<Turn>): ChatSession {
        require(turns.isNotEmpty()) { "Cannot create a session with no turns" }
        val now = System.currentTimeMillis()
        val session =
            ChatSession(
                id = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
                title = titleFor(turns),
                turns = turns,
            )
        write(session)
        prune()
        return session
    }

    /** Overwrites an existing session's turns, refreshing its title and
     *  updatedAt. If [id] has no existing file (e.g. it was deleted
     *  elsewhere), this recreates it under the same id rather than failing.
     */
    fun update(
        id: String,
        turns: List<Turn>,
    ): ChatSession {
        require(turns.isNotEmpty()) { "Cannot save a session with no turns" }
        val existing = load(id)
        val session =
            ChatSession(
                id = id,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                title = titleFor(turns),
                turns = turns,
            )
        write(session)
        return session
    }

    fun load(id: String): ChatSession? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return runCatching { SessionCodec.decode(file.readText()) }.getOrNull()
    }

    /** Most-recently-updated first. Any file that fails to parse (corrupt,
     *  partial write) is silently skipped rather than breaking the whole
     *  listing for one bad entry.
     */
    fun list(): List<SessionSummary> {
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
        return files
            .mapNotNull { f -> runCatching { SessionCodec.decode(f.readText()) }.getOrNull() }
            .map { SessionSummary(it.id, it.title, it.updatedAt) }
            .sortedByDescending { it.updatedAt }
    }

    fun delete(id: String): Boolean = fileFor(id).delete()

    /** Removes every saved session file. Returns how many were deleted. */
    fun deleteAll(): Int {
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return 0
        return files.count { it.delete() }
    }

    private fun write(session: ChatSession) {
        baseDir.mkdirs()
        fileFor(session.id).writeText(SessionCodec.encode(session))
    }

    /** Deletes the oldest-updated sessions beyond [MAX_SESSIONS]. Files that
     *  fail to parse are left alone, same as [list] ignoring them.
     */
    private fun prune() {
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return
        val decoded = files.mapNotNull { f -> runCatching { f to SessionCodec.decode(f.readText()) }.getOrNull() }
        if (decoded.size <= MAX_SESSIONS) return
        decoded
            .sortedByDescending { (_, session) -> session.updatedAt }
            .drop(MAX_SESSIONS)
            .forEach { (file, _) -> file.delete() }
    }

    private fun titleFor(turns: List<Turn>): String {
        val firstUserText = turns.firstOrNull { it.speaker == Speaker.USER }?.text ?: turns.first().text
        val trimmed = firstUserText.trim()
        val truncated = if (trimmed.length > TITLE_MAX_LENGTH) trimmed.take(TITLE_MAX_LENGTH).trimEnd() + "..." else trimmed
        return truncated.ifEmpty { "New conversation" }
    }
}
