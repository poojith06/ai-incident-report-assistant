package com.example.test_app.media

/**
 * Pure rules for photos picked from the gallery: at most [MAX_PHOTOS] per report. The system picker is asked for at most that many; the
 * fallback picker (API < 33 without the photo-picker backport) cannot limit, so [accept] cuts the list. See `doc/UI_FLOW.md`.
 */
object PhotoPicks {
    const val MAX_PHOTOS = 3

    /** How many of [picked] photos to take when [alreadyAttached] are attached: `accepted`, and `dropped` = the surplus the user must be told about. */
    data class Decision(val accepted: Int, val dropped: Int)

    fun accept(alreadyAttached: Int, picked: Int): Decision {
        val room = (MAX_PHOTOS - alreadyAttached).coerceAtLeast(0)
        val take = picked.coerceAtLeast(0).coerceAtMost(room)
        return Decision(take, picked.coerceAtLeast(0) - take)
    }

    /** User-facing text for [Decision.dropped] photos that did not fit, or null when everything fit. */
    fun droppedNotice(decision: Decision): String? = when (decision.dropped) {
        0 -> null
        else -> "Only $MAX_PHOTOS photos can be added to one report; ${decision.dropped} ${if (decision.dropped == 1) "was" else "were"} left out."
    }
}
