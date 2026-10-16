package com.example.test_app.ui.admin

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.test_app.admin.AdminPin
import com.example.test_app.ui.theme.AppTheme

/**
 * The admin lock dialog: asks for the 4-digit PIN. [onCheck] returns whether it was right. After [AdminPin.MAX_ATTEMPTS] wrong entries
 * entering is paused for [PAUSE_MS]. This is a DEMO gate, not real security (`doc/ADMIN_DASHBOARD.md`).
 */
@Composable
fun AdminPinDialog(onCheck: (String) -> Boolean, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }
    var pausedUntil by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val now = System.currentTimeMillis()
        if (now < pausedUntil) { message = "Too many wrong PINs. Wait ${(pausedUntil - now) / 1000 + 1} seconds."; return }
        if (onCheck(pin)) { onSuccess(); return }
        wrong++; pin = ""
        if (wrong >= AdminPin.MAX_ATTEMPTS) { wrong = 0; pausedUntil = now + PAUSE_MS; message = "Too many wrong PINs. Wait ${PAUSE_MS / 1000} seconds." } else message = "Wrong PIN."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin PIN") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("Enter the 4-digit PIN to open the admin dashboard.", style = AppTheme.type.caption, color = c.mutedText)
                OutlinedTextField(
                    value = pin, onValueChange = { v -> pin = v.filter { it in '0'..'9' }.take(AdminPin.LENGTH); message = null }, singleLine = true,
                    label = { Text("PIN") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), isError = message != null,
                    supportingText = message?.let { m -> { Text(m, color = c.error) } },
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = pin.length == AdminPin.LENGTH) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = c.surfaceElevated, titleContentColor = c.onSurface, textContentColor = c.mutedText,
    )
}

private const val PAUSE_MS = 30_000L
