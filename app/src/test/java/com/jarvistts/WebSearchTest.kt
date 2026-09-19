package com.jarvistts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSearchTest {
    @Test
    fun parsesExtractFromWikipediaResponse() {
        val json = "{\"query\":{\"pages\":{\"123\":{\"title\":\"X\",\"extract\":\"X is a thing. It is nice.\"}}}}"
        assertEquals("X is a thing. It is nice.", WebSearch.parseExtract(json))
    }

    @Test
    fun noPagesMeansNull() {
        assertNull(WebSearch.parseExtract("{\"batchcomplete\":\"\"}"))
    }

    @Test
    fun keepsFirstTwoSentencesAndDropsParentheticals() {
        val text = "Alan Turing (23 June 1912 - 7 June 1954) was an English mathematician. He was a pioneer. He died young."
        assertEquals("Alan Turing was an English mathematician. He was a pioneer.", WebSearch.firstSentences(text, 2))
    }

    @Test
    fun routesSearchPhrases() {
        assertEquals(mapOf("query" to "the Eiffel Tower"), Tools.route("Search for the Eiffel Tower.")?.args)
        assertEquals("web_search", Tools.route("look up Alan Turing")?.name)
        assertEquals("Ada Lovelace", Tools.route("Who was Ada Lovelace?")?.args?.get("query"))
    }
}
