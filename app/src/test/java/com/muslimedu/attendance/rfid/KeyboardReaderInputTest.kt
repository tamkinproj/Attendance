package com.muslimedu.attendance.rfid

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard-emulation reader must never take keys from the on-screen
 * keyboard: Gboard's number pad sends digits as key events, and eating them
 * made the admin PIN impossible to type.
 */
class KeyboardReaderInputTest {

    @Test
    fun `on-screen keyboard keys are never reader input`() {
        assertTrue(KeyboardEmulationRfidReader.isSoftKeyboardInput(KeyCharacterMap.VIRTUAL_KEYBOARD, 0, null))
        assertTrue(KeyboardEmulationRfidReader.isSoftKeyboardInput(7, KeyEvent.FLAG_SOFT_KEYBOARD, false))
        assertTrue(KeyboardEmulationRfidReader.isSoftKeyboardInput(7, 0, true))
    }

    @Test
    fun `a physical USB keyboard device can be a reader`() {
        assertFalse(KeyboardEmulationRfidReader.isSoftKeyboardInput(7, 0, false))
        assertFalse(KeyboardEmulationRfidReader.isSoftKeyboardInput(7, 0, null))
    }
}
