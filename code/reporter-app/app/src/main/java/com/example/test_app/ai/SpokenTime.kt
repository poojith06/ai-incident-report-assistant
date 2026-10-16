package com.example.test_app.ai

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Turns the user's own words for a time ("around 10 AM", "5 minutes ago", "last night") into a timestamp relative to
 * [now] (same offset). Pure Kotlin. Returns null when nothing recognisable is said: the app NEVER invents a time.
 *
 * Clock times need "am"/"pm" or a colon ("10 am", "10:30", "14:30"); a bare number is not a time ("room 3").
 * Conventions for vague phrases (documented, tested):
 * - "just now", "now", "right now", "a moment ago" -> now
 * - "N minutes/hours/days ago" (N digits, number word, a/an, "a couple of") and "half an hour ago" -> now minus that
 * - "this morning" 09:00, "this afternoon" 15:00, "this evening" 18:00, "last night" yesterday 21:00,
 *   "yesterday" 12:00, "yesterday morning" 09:00, "yesterday afternoon" 15:00, "yesterday evening" 18:00
 * - an explicit clock time wins over the vague part of day ("yesterday at 3 pm" = yesterday 15:00)
 * - a clock time (or part of day) that would be later than [now] today means the previous day (a report is never about the future)
 */
object SpokenTime {
    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun format(time: OffsetDateTime): String = ISO.format(time.truncatedTo(ChronoUnit.SECONDS))

    private val NUMBER_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "couple" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45, "sixty" to 60,
    )

    private val NOW = Regex("\\b(?:just\\s+now|right\\s+now|now|a\\s+moment\\s+ago|just\\s+a\\s+moment\\s+ago|moments?\\s+ago)\\b")
    private val HALF_HOUR = Regex("\\bhalf\\s+an?\\s+hour\\s+ago\\b")
    private val AGO = Regex(
        "\\b(\\d{1,3}|[a-z]+(?:-[a-z]+)?)\\s+(?:of\\s+)?(minutes?|mins?|hours?|hrs?|days?)\\s+(?:ago|back)\\b",
    )
    private val CLOCK = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?![\\w:])")

    fun parse(text: String?, now: OffsetDateTime): OffsetDateTime? {
        if (text.isNullOrBlank()) return null
        val s = text.lowercase().replace('\u2019', '\'').trim()
        val base = now.truncatedTo(ChronoUnit.SECONDS)

        HALF_HOUR.find(s)?.let { return base.minusMinutes(30) }
        AGO.find(s)?.let { m ->
            val raw = m.groupValues[1]
            val n = raw.toIntOrNull() ?: NUMBER_WORDS[raw]
            if (n != null) {
                val unit = m.groupValues[2]
                return when {
                    unit.startsWith("min") -> base.minusMinutes(n.toLong())
                    unit.startsWith("h") -> base.minusHours(n.toLong())
                    else -> base.minusDays(n.toLong())
                }
            }
        }

        val yesterday = "yesterday" in s
        val lastNight = Regex("\\blast\\s+night\\b").containsMatchIn(s)
        val explicit = findClock(s)
        val partOfDay: Pair<Int, Int>? = when {
            lastNight -> 21 to 0
            "morning" in s -> 9 to 0
            "afternoon" in s -> 15 to 0
            "evening" in s -> 18 to 0
            yesterday -> 12 to 0
            else -> null
        }
        val clock = explicit ?: partOfDay
        if (clock != null) {
            val day = if (yesterday || lastNight) base.minusDays(1) else base
            var result = day.withHour(clock.first).withMinute(clock.second).withSecond(0).withNano(0)
            if (result.isAfter(base) && !yesterday && !lastNight) {
                // an explicit clock time later than now means yesterday; a vague part of day ("this morning") is capped at now
                result = if (explicit != null) result.minusDays(1) else base
            }
            return result
        }

        if (NOW.containsMatchIn(s)) return base
        return null
    }

    /** (hour, minute) of the first recognisable clock time, or null. */
    private fun findClock(s: String): Pair<Int, Int>? {
        for (m in CLOCK.findAll(s)) {
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            val marker = m.groupValues[3].replace(".", "")
            if (minute > 59) continue
            if (marker.isNotEmpty()) {
                if (hour !in 1..12) continue
                val h24 = (hour % 12) + if (marker == "pm") 12 else 0
                return h24 to minute
            }
            if (m.groupValues[2].isNotEmpty() && hour in 0..23) return hour to minute
        }
        return null
    }
}
