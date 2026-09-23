package com.muslimedu.attendance.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun `correct pin matches, wrong pin and different salt do not`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("4821", salt, iterations = 1_000)

        assertTrue(PinHasher.matches("4821", salt, 1_000, hash))
        assertFalse(PinHasher.matches("4822", salt, 1_000, hash))
        assertFalse(PinHasher.matches("4821", PinHasher.newSalt(), 1_000, hash))
    }

    @Test
    fun `pin format is 4 to 8 digits only`() {
        assertTrue(PinHasher.isValidFormat("1234"))
        assertTrue(PinHasher.isValidFormat("12345678"))
        assertFalse(PinHasher.isValidFormat("123"))
        assertFalse(PinHasher.isValidFormat("123456789"))
        assertFalse(PinHasher.isValidFormat("12a4"))
    }
}
