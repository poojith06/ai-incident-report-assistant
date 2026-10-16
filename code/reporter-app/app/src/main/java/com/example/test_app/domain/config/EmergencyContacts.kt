package com.example.test_app.domain.config

/** A person staff can call when an incident is severe. See `doc/DATA_SCHEMA.md` section "Emergency contacts". */
data class EmergencyContact(val name: String, val role: String, val phone: String)

/**
 * Emergency contact list (DUMMY numbers for the demo; replace with the organisation's directory).
 * The contacts are offered on the Review and Success screens when severity is High or Critical.
 */
object EmergencyContacts {
    val DUMMY = listOf(
        EmergencyContact("Rajesh Kumar", "Security supervisor", "+91 98765 43210"),
        EmergencyContact("Anita Sharma", "HR manager", "+91 98100 22334"),
        EmergencyContact("Suresh Patil", "Facilities and maintenance", "+91 99876 54321"),
        EmergencyContact("Lakshmi Devi", "Cleaning staff lead", "+91 97410 55667"),
        EmergencyContact("Dr. Meera Nair", "First-aid and medical desk", "+91 90000 11223"),
    )

    /** Severities that trigger the "call for help" option. */
    private val SEVERE = setOf("High", "Critical")

    fun isSevere(severity: String?): Boolean = severity in SEVERE
}
