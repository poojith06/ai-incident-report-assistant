package com.example.test_app.admin

/** The admin dashboard's small persisted settings (SharedPreferences in the app, memory in tests). */
interface AdminPrefs {
    /** The stored PIN, or null when never changed (the default applies, see [AdminPin]). */
    var pin: String?

    /** "Also delete KG audit and outbox when deleting reports" (default on). */
    var alsoDeleteKgFiles: Boolean

    /** [AnalysisSettings] as JSON, or null for the defaults. */
    var analysisSettingsJson: String?
}

class InMemoryAdminPrefs(override var pin: String? = null, override var alsoDeleteKgFiles: Boolean = true, override var analysisSettingsJson: String? = null) : AdminPrefs
