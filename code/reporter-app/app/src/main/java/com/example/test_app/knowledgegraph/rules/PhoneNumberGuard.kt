package com.example.test_app.knowledgegraph.rules

/** Spec section 3 and 13: a phone number may only be shown when it is the number of a verified, enabled, unexpired contact. */
object PhoneNumberGuard {
    private val LONG_NUMBER = Regex("\\+?\\d[\\d\\s().-]{4,}\\d")
    private val SHORT_AFTER_CALL_WORD = Regex("\\b(call|dial|ext\\.?|extension|phone|tel|helpline|number)\\b\\s*[:#-]?\\s*(\\d{3,})", RegexOption.IGNORE_CASE)

    private fun digits(s: String) = s.filter { it.isDigit() }

    /** Phone-like numbers in [text] (digit groups only) that are not in [verifiedNumbers] (compared by digits only). */
    fun unverifiedNumbers(text: String, verifiedNumbers: Collection<String> = emptyList()): List<String> {
        val verified = verifiedNumbers.map { digits(it) }.toSet()
        val found = LONG_NUMBER.findAll(text).map { it.value.trim() }.filter { digits(it).length >= 6 }.toMutableList()
        SHORT_AFTER_CALL_WORD.findAll(text).forEach { found += it.groupValues[2] }
        return found.filter { digits(it) !in verified }.distinct()
    }

    fun containsUnverifiedNumber(text: String, verifiedNumbers: Collection<String> = emptyList()) = unverifiedNumbers(text, verifiedNumbers).isNotEmpty()
}
