package com.shohan.deadzonemirror.viewmodel

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import com.shohan.deadzonemirror.TouchAccessibilityService
import com.shohan.deadzonemirror.isAccessibilityServiceEnabled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MainUiState(
    val overlayPermissionGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val isMirrorRunning: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** Re-checks real permission state; call from onResume since the user
     *  grants these from a separate Settings screen. */
    fun refreshPermissionState(context: Context) {
        _uiState.value = _uiState.value.copy(
            overlayPermissionGranted = Settings.canDrawOverlays(context),
            accessibilityEnabled = isAccessibilityServiceEnabled(
                context,
                TouchAccessibilityService::class.java
            )
        )
    }

    fun setMirrorRunning(running: Boolean) {
        _uiState.value = _uiState.value.copy(isMirrorRunning = running)
    }
}
