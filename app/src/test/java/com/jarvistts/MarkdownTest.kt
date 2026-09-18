package com.jarvistts

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun `parse leaves plain text as a single plain span`() {
        val blocks = Markdown.parse("Nothing special here.")
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan.Plain("Nothing special here.")))), blocks)
    }

    @Test
    fun `parse splits bold text into plain and bold spans`() {
        val blocks = Markdown.parse("This is **important**, got it?")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(
                    listOf(
                        MarkdownSpan.Plain("This is "),
                        MarkdownSpan.Bold("important"),
                        MarkdownSpan.Plain(", got it?"),
                    ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `parse splits inline code into a code span`() {
        val blocks = Markdown.parse("Run `ls -la` first.")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(
                    listOf(
                        MarkdownSpan.Plain("Run "),
                        MarkdownSpan.Code("ls -la"),
                        MarkdownSpan.Plain(" first."),
                    ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `parse pulls a fenced code block out as its own block`() {
        val blocks = Markdown.parse("Here:\n```\nprint(1)\n```\nThat's it.")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(listOf(MarkdownSpan.Plain("Here:"))),
                MarkdownBlock.CodeBlock("print(1)"),
                MarkdownBlock.Paragraph(listOf(MarkdownSpan.Plain("That's it."))),
            ),
            blocks,
        )
    }

    @Test
    fun `parse drops a language tag on a fenced code block`() {
        val blocks = Markdown.parse("```kotlin\nval x = 1\n```")
        assertEquals(listOf(MarkdownBlock.CodeBlock("val x = 1")), blocks)
    }

    @Test
    fun `parse strips header markers`() {
        val blocks = Markdown.parse("# Heading\nBody text.")
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan.Plain("Heading\nBody text.")))),
            blocks,
        )
    }

    @Test
    fun `parse keeps link text and drops the url`() {
        val blocks = Markdown.parse("See [the docs](https://example.com) for more.")
        assertEquals(
            listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan.Plain("See the docs for more.")))),
            blocks,
        )
    }

    @Test
    fun `stripForSpeech removes bold markers but keeps the words`() {
        assertEquals("This is important.", Markdown.stripForSpeech("This is **important**."))
    }

    @Test
    fun `stripForSpeech removes inline code backticks`() {
        assertEquals("Run ls -la now.", Markdown.stripForSpeech("Run `ls -la` now."))
    }

    @Test
    fun `stripForSpeech removes fenced code block markers but keeps the code`() {
        assertEquals("print(1)", Markdown.stripForSpeech("```python\nprint(1)\n```"))
    }

    @Test
    fun `stripForSpeech removes headers`() {
        assertEquals("Heading\nBody text.", Markdown.stripForSpeech("## Heading\nBody text."))
    }

    @Test
    fun `stripForSpeech keeps link text and drops the url`() {
        assertEquals("See the docs for more.", Markdown.stripForSpeech("See [the docs](https://example.com) for more."))
    }

    @Test
    fun `stripForSpeech leaves plain speech untouched`() {
        assertEquals("What time is it?", Markdown.stripForSpeech("What time is it?"))
    }
}
