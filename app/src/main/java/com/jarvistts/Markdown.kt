package com.jarvistts

/** Minimal markdown handling for LLM replies: just enough to keep **bold**,
 *  `inline code`, ```fenced code```, [links](url), and # headers from
 *  showing up as literal punctuation on screen or in what TTS reads aloud.
 *  Not a CommonMark implementation -- the local model's replies are short
 *  and rarely nest formatting, so a handful of regexes cover what actually
 *  shows up in practice.
 */
sealed class MarkdownSpan {
    data class Plain(val text: String) : MarkdownSpan()

    data class Bold(val text: String) : MarkdownSpan()

    data class Code(val text: String) : MarkdownSpan()
}

sealed class MarkdownBlock {
    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock()

    data class CodeBlock(val code: String) : MarkdownBlock()
}

object Markdown {
    // Language tag only consumed when followed by a newline, so a same-line
    // ```like this``` doesn't get misread as an empty block with the whole
    // payload swallowed as a "language".
    private val CODE_FENCE = Regex("```(?:[a-zA-Z0-9_+-]*\n)?([\\s\\S]*?)```")
    private val HEADER = Regex("(?m)^#{1,6}\\s+")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val BOLD_OR_CODE = Regex("\\*\\*(.+?)\\*\\*|`([^`\n]+?)`")

    /** Splits reply text into paragraphs (with inline spans) and fenced code
     *  blocks, for rendering as formatted text in the transcript.
     */
    fun parse(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var last = 0
        for (m in CODE_FENCE.findAll(text)) {
            val before = text.substring(last, m.range.first)
            if (before.isNotBlank()) blocks.add(MarkdownBlock.Paragraph(parseInline(before.trim())))
            blocks.add(MarkdownBlock.CodeBlock(m.groupValues[1].trim('\n')))
            last = m.range.last + 1
        }
        val rest = text.substring(last)
        if (rest.isNotBlank()) blocks.add(MarkdownBlock.Paragraph(parseInline(rest.trim())))
        if (blocks.isEmpty()) blocks.add(MarkdownBlock.Paragraph(parseInline(text)))
        return blocks
    }

    private fun parseInline(text: String): List<MarkdownSpan> {
        val cleaned = text.replace(HEADER, "").replace(LINK) { it.groupValues[1] }
        val spans = mutableListOf<MarkdownSpan>()
        var last = 0
        for (m in BOLD_OR_CODE.findAll(cleaned)) {
            if (m.range.first > last) spans.add(MarkdownSpan.Plain(cleaned.substring(last, m.range.first)))
            val bold = m.groups[1]
            spans.add(if (bold != null) MarkdownSpan.Bold(bold.value) else MarkdownSpan.Code(m.groups[2]!!.value))
            last = m.range.last + 1
        }
        if (last < cleaned.length) spans.add(MarkdownSpan.Plain(cleaned.substring(last)))
        if (spans.isEmpty()) spans.add(MarkdownSpan.Plain(cleaned))
        return spans
    }

    /** Strips markdown syntax down to the words underneath, for text handed to
     *  the TTS engine -- Jarvis should never say "asterisk asterisk" or read
     *  a code fence aloud.
     */
    fun stripForSpeech(text: String): String {
        var out = text.replace(CODE_FENCE) { it.groupValues[1].trim('\n') }
        out = out.replace(LINK) { it.groupValues[1] }
        out = out.replace(HEADER, "")
        out = out.replace(Regex("\\*\\*(.+?)\\*\\*")) { it.groupValues[1] }
        out = out.replace(Regex("`([^`\n]+?)`")) { it.groupValues[1] }
        return out.trim()
    }
}
