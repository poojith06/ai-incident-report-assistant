package com.example.test_app.platform

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.test_app.domain.model.DeviceInfo
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Supplies the device-sourced part of a report: reporter, report time, GPS.
 * GPS is currently always null (not implemented in this phase; see `doc/TODO.md`).
 */
class DeviceContext(private val context: Context) {
    fun snapshot(): DeviceInfo {
        val name = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return DeviceInfo(
            reporter = name?.takeIf { it.isNotBlank() } ?: Build.MODEL,
            reportedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            gps = null,
            anonymous = false,
        )
    }
}
