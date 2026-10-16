package com.example.test_app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.test_app.admin.AdminViewModel
import com.example.test_app.flow.IncidentFlowViewModel
import com.example.test_app.ui.AppRoot
import com.example.test_app.ui.theme.IncidentAssistantTheme

/** Application class: owns the [AppContainer] (manual DI). See `doc/ARCHITECTURE.md`. */
class IncidentApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Fail loudly on a malformed dialogue asset in debuggable builds (Spec v2 section 11 step 1).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) container.dialogueAssets
        // Real AI only: load the model once, off the main thread (a no-op in Mock AI mode).
        container.ensureModelsLoading()
    }
}

/** Single activity; all navigation is state-driven inside [AppRoot]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as IncidentApp).container
        setContent {
            IncidentAssistantTheme {
                val vm: IncidentFlowViewModel = viewModel(
                    factory = viewModelFactory { initializer { IncidentFlowViewModel(container) } },
                )
                val admin: AdminViewModel = viewModel(
                    factory = viewModelFactory { initializer { AdminViewModel(container.adminBackend, container.adminPrefs) } },
                )
                AppRoot(vm, admin)
            }
        }
    }
}
