package com.muslimedu.attendance.rfid

import java.util.Locale

/**
 * The one form a card UID is stored and compared in: trimmed, upper-case.
 * Keyboard-emulation readers type hex in whatever case they like, and the
 * server's card registry uses the same rule, so "04:a1:b2" read here and
 * "04:A1:B2" downloaded from the web admin are the same card.
 */
fun normalizeRfidUid(raw: String): String = raw.trim().uppercase(Locale.ROOT)
