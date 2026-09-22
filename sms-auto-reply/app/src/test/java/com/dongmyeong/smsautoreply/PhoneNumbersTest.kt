package com.dongmyeong.smsautoreply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun `domestic formats normalise to plain digits`() {
        assertEquals("01012345678", PhoneNumbers.normalize("010-1234-5678"))
        assertEquals("01012345678", PhoneNumbers.normalize("010 1234 5678"))
        assertEquals("01012345678", PhoneNumbers.normalize("01012345678"))
    }

    @Test
    fun `international korean formats collapse to domestic`() {
        assertEquals("01012345678", PhoneNumbers.normalize("+821012345678"))
        assertEquals("01012345678", PhoneNumbers.normalize("+82 10-1234-5678"))
        assertEquals("01012345678", PhoneNumbers.normalize("821012345678"))
        assertEquals("01012345678", PhoneNumbers.normalize("00821012345678"))
    }

    @Test
    fun `non numeric senders are rejected`() {
        assertNull(PhoneNumbers.normalize(null))
        assertNull(PhoneNumbers.normalize(""))
        assertNull(PhoneNumbers.normalize("KakaoBank"))
    }

    @Test
    fun `matches compares normalised values`() {
        assertTrue(PhoneNumbers.matches("010-1234-5678", "+821012345678"))
        assertFalse(PhoneNumbers.matches("010-1234-5678", "010-1234-5679"))
        assertFalse(PhoneNumbers.matches("010-1234-5678", null))
    }
}
