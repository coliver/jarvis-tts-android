package com.jarvistts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {
    @Test
    fun routesTimeQuestions() {
        listOf("What time is it?", "Tell me the current time.", "what's the date", "What day is it?").forEach {
            assertEquals(it, "get_time", Tools.route(it)?.name)
        }
    }

    @Test
    fun routesBattery() {
        assertEquals("get_battery", Tools.route("How much battery do I have left?")?.name)
    }

    @Test
    fun routesTimerWithDigitsAndWords() {
        assertEquals(mapOf("seconds" to "120"), Tools.route("Set a timer for 2 minutes.")?.args)
        assertEquals(mapOf("seconds" to "300"), Tools.route("set a timer for five minutes")?.args)
        assertEquals(mapOf("seconds" to "5400"), Tools.route("start a timer for 1 hour and 30 minutes")?.args)
    }

    @Test
    fun timerWithoutDurationIsNotRouted() {
        assertNull(Tools.route("what is a timer"))
    }

    @Test
    fun routesEmailWithBody() {
        val call = Tools.route("Send an email to chris at gmail dot com saying I'll be late")
        assertEquals("send_email", call?.name)
        assertEquals("chris at gmail dot com", call?.args?.get("to"))
        assertEquals("I'll be late", call?.args?.get("body"))
    }

    @Test
    fun routesEmailWithoutBody() {
        assertEquals("a at b dot com", Tools.route("write an email to a at b dot com")?.args?.get("to"))
    }

    @Test
    fun ordinaryQuestionsFallThrough() {
        assertNull(Tools.route("Why is the sky blue?"))
        assertNull(Tools.route("Not even close."))
    }

    @Test
    fun normalizesSpokenEmail() {
        assertEquals("chris@gmail.com", Tools.normalizeSpokenEmail("chris at gmail dot com."))
    }

    @Test
    fun validatesEmail() {
        assertTrue(Tools.isPlausibleEmail("a@b.com"))
        assertFalse(Tools.isPlausibleEmail("a@b"))
        assertFalse(Tools.isPlausibleEmail("hello"))
    }

    @Test
    fun describesDurations() {
        assertEquals("2 minutes", Tools.describeDuration(120))
        assertEquals("1 hour and 30 minutes", Tools.describeDuration(5400))
    }
}
