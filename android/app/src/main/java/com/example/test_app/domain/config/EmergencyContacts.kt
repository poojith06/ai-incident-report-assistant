package com.example.test_app.domain.config

/** A person staff can call when an incident is severe. See `doc/DATA_SCHEMA.md` section "Emergency contacts". */
data class EmergencyContact(val name: String, val role: String, val phone: String)

/**
 * Emergency contact list (DUMMY numbers for the demo; replace with the organisation's directory).
 * The contacts are offered on the Review and Success screens when severity is High or Critical.
 */
object EmergencyContacts {
    val DUMMY = listOf(
        EmergencyContact("Security desk (demo)", "Security supervisor", "+91 00000 00001"),
        EmergencyContact("HR office (demo)", "HR manager", "+91 00000 00002"),
        EmergencyContact("Facilities desk (demo)", "Facilities and maintenance", "+91 00000 00003"),
        EmergencyContact("Housekeeping (demo)", "Cleaning staff lead", "+91 00000 00004"),
        EmergencyContact("Medical desk (demo)", "First-aid and medical desk", "+91 00000 00005"),
    )

    /** Severities that trigger the "call for help" option. */
    private val SEVERE = setOf("High", "Critical")

    fun isSevere(severity: String?): Boolean = severity in SEVERE
}
