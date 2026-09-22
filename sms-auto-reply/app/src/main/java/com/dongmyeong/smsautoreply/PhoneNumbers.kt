package com.dongmyeong.smsautoreply

/**
 * Normalises Korean phone numbers so that "010-1234-5678", "01012345678",
 * "+82 10 1234 5678" and "82-10-1234-5678" all compare equal.
 *
 * Returns null for senders that are not phone numbers (e.g. alphanumeric
 * sender IDs used by some carriers or e-mail gateways).
 */
object PhoneNumbers {
    private const val KR_COUNTRY_CODE = "82"

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val hasPlus = raw.trimStart().startsWith("+")
        var digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        // "0082..." (international dialling prefix) -> "82..."
        if (digits.startsWith("00$KR_COUNTRY_CODE")) digits = digits.drop(2)

        // "+8210..." or "8210..." -> "010..."
        if (digits.startsWith(KR_COUNTRY_CODE) && (hasPlus || digits.length >= 11)) {
            digits = "0" + digits.drop(KR_COUNTRY_CODE.length)
        }

        return digits
    }

    fun matches(a: String?, b: String?): Boolean {
        val na = normalize(a) ?: return false
        val nb = normalize(b) ?: return false
        return na == nb
    }
}
