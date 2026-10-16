package com.example.test_app.admin

/**
 * The admin gate: a 4-digit PIN (default `0000`). It is a DEMO gate to keep casual users out of the admin screens, NOT real security: the
 * PIN sits unencrypted in the app's preferences and the data is plain files. Pure functions; the preferences are wired by `AppContainer`.
 * See `doc/ADMIN_DASHBOARD.md` and ADR-058.
 */
object AdminPin {
    const val DEFAULT = "0000"
    const val LENGTH = 4

    fun isValidFormat(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }

    /** The stored PIN, or [DEFAULT] when nothing (or something malformed) is stored. */
    fun effective(stored: String?): String = stored?.takeIf { isValidFormat(it) } ?: DEFAULT

    /** true when [entered] is the PIN. */
    fun check(entered: String, stored: String?): Boolean = isValidFormat(entered) && entered == effective(stored)

    /** Result of a PIN change. */
    sealed interface Change {
        data class Ok(val newPin: String) : Change
        data class Rejected(val reason: String) : Change
    }

    fun change(current: String, newPin: String, confirm: String, stored: String?): Change = when {
        !check(current, stored) -> Change.Rejected("The current PIN is wrong.")
        !isValidFormat(newPin) -> Change.Rejected("The new PIN must be exactly $LENGTH digits.")
        newPin != confirm -> Change.Rejected("The two new PINs are different.")
        newPin == effective(stored) -> Change.Rejected("The new PIN is the same as the current one.")
        else -> Change.Ok(newPin)
    }

    /** Tries left before the lock dialog pauses (the UI counts wrong entries; a pause only slows guessing). */
    const val MAX_ATTEMPTS = 5
}
