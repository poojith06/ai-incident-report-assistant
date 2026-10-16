package com.example.test_app.knowledgegraph.rules

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Parses the ISO-8601 timestamps and dates of the graph (approvals, expiries, incident times). Null for blank or unreadable text. */
object Instants {
    fun parse(text: String?): Instant? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(t).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(t) }.getOrNull()
            ?: runCatching { LocalDate.parse(t).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(t).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    /** true when [expiresAt] is set and is not after [now]; an expiry that cannot be read counts as expired (fail closed). */
    fun isExpired(expiresAt: String?, now: Instant): Boolean {
        if (expiresAt.isNullOrBlank()) return false
        val parsed = parse(expiresAt) ?: return true
        return !parsed.isAfter(now)
    }
}
