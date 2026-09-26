package com.muslimedu.attendance.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A Philippine mobile number in the one form the server stores, 09XXXXXXXXX,
 * or null when [raw] isn't one (a landline, a short code, a typo). Takes what
 * people type: "0917 123 4567", "+63 917-123-4567", "639171234567", or
 * "9171234567" with the leading 0 lost. Same rule as the server's
 * PhMobileNumber::normalize.
 */
fun normalizePhMobile(raw: String): String? {
    var digits = raw.filter(Char::isDigit)
    if (digits.length == 12 && digits.startsWith("639")) {
        digits = "0" + digits.substring(2)
    } else if (digits.length == 10 && digits.startsWith("9")) {
        digits = "0$digits"
    }
    return digits.takeIf { it.length == 11 && it.startsWith("09") }
}

/** "0917 123 4567" - easier to read back to a parent than eleven digits in a row. */
fun formatPhMobile(phone: String): String =
    if (phone.length == 11) "${phone.substring(0, 4)} ${phone.substring(4, 7)} ${phone.substring(7)}" else phone

/**
 * The parent text for one scan, rendered the way the server does
 * (GateSmsTemplate::render) so the Parent SMS preview shows exactly what
 * parents will receive. [time] is the gate's "HH:mm", shown as "7:42 AM";
 * [date] is "yyyy-MM-dd", shown as "Sep 26, 2026".
 */
object SmsTemplate {
    const val DEFAULT_IN = "{school}: Ang inyong anak na si {student} ay pumasok sa paaralan ng {time} ({date})."
    const val DEFAULT_OUT = "{school}: Ang inyong anak na si {student} ay lumabas ng paaralan ng {time} ({date})."

    /** Sent instead of the Coming In text when the scan was after its "Late after" time. */
    const val DEFAULT_LATE = "{school}: Ang inyong anak na si {student} ay pumasok sa paaralan ng {time} ({date}) - huli ng {minutes_late} minuto."

    /** Sent by the server at the "not arrived" cutoff to parents of students with no scan yet; {time} is the cutoff. */
    const val DEFAULT_ABSENT = "{school}: Ang inyong anak na si {student} ay hindi pa pumapasok sa paaralan hanggang {time} ({date})."
    /** The texts start with the school's name, so parents see who it's from - the phone gateway's sender is only a number. */
    private val LEADING_SEPARATOR = Regex("""^[:\-\s]+""")
    val PLACEHOLDERS = listOf("{student}", "{code}", "{time}", "{date}", "{school}", "{minutes_late}")

    /** GSM-7 text: 160 characters fit one SMS; longer texts are sent as 153-character parts. */
    fun segments(text: String): Int = when {
        text.isEmpty() -> 0
        text.length <= 160 -> 1
        else -> (text.length + 152) / 153
    }

    fun render(
        template: String,
        student: String,
        code: String,
        time: String,
        date: String,
        school: String?,
        minutesLate: Int? = null,
    ): String =
        template
            .replace("{student}", student)
            .replace("{code}", code)
            .replace("{time}", formatTime(time))
            .replace("{date}", formatDate(date))
            .replace("{school}", school.orEmpty())
            .replace("{minutes_late}", minutesLate?.toString().orEmpty())
            .trim()
            // No school name: drop the "{school}: " prefix's leftover ": ".
            .replace(LEADING_SEPARATOR, "")

    fun formatTime(time: String): String = runCatching {
        LocalTime.parse(time.take(5), DateTimeFormatter.ofPattern("HH:mm"))
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    }.getOrDefault(time)

    fun formatDate(date: String): String = runCatching {
        LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    }.getOrDefault(date)
}
