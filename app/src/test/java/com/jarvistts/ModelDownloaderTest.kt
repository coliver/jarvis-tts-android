package com.jarvistts

import com.jarvistts.ModelDownloader.toHexString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class ModelDownloaderTest {
    @Test
    fun `toHexString renders digest bytes as lowercase hex`() {
        val digest = MessageDigest.getInstance("SHA-256").digest("hello".toByteArray())

        val hex = digest.toHexString()

        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            hex,
        )
    }

    @Test
    fun `toHexString pads single-digit bytes with a leading zero`() {
        val bytes = byteArrayOf(0x00, 0x0f, -0x01)

        assertEquals("000fff", bytes.toHexString())
    }
}
