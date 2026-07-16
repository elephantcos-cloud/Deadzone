package com.shohan.deadzonemirror

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shohan.deadzonemirror.ui.theme.DeadZoneMirrorTheme
import com.shohan.deadzonemirror.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissionState(this)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way; foreground service still starts, just without a visible prompt path if denied */ }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val projectionData = result.data
        if (result.resultCode == Activity.RESULT_OK && projectionData != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, projectionData)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            viewModel.setMirrorRunning(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            DeadZoneMirrorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestOverlayPermission = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            overlayPermissionLauncher.launch(intent)
                        },
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onStartMirror = {
                            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        },
                        onStopMirror = {
                            startService(
                                Intent(this, OverlayService::class.java).apply {
                                    action = OverlayService.ACTION_STOP
                                }
                            )
                            viewModel.setMirrorRunning(false)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionState(this)
    }
}

@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartMirror: () -> Unit,
    onStopMirror: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(text = stringResource(id = R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        PermissionRow(
            label = stringResource(
                if (state.overlayPermissionGranted) R.string.permission_overlay_granted
                else R.string.permission_overlay_not_granted
            ),
            actionLabel = stringResource(R.string.grant_overlay_permission),
            showAction = !state.overlayPermissionGranted,
            onAction = onRequestOverlayPermission
        )

        PermissionRow(
            label = stringResource(
                if (state.accessibilityEnabled) R.string.permission_accessibility_granted
                else R.string.permission_accessibility_not_granted
            ),
            actionLabel = stringResource(R.string.enable_accessibility),
            showAction = !state.accessibilityEnabled,
            onAction = onOpenAccessibilitySettings
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { if (state.isMirrorRunning) onStopMirror() else onStartMirror() },
            enabled = state.overlayPermissionGranted && state.accessibilityEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    if (state.isMirrorRunning) R.string.stop_mirror else R.string.start_mirror
                )
            )
        }

        if (!state.overlayPermissionGranted || !state.accessibilityEnabled) {
            Text(
                text = stringResource(R.string.setup_hint),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (state.isMirrorRunning) {
            Text(
                text = stringResource(R.string.edit_mode_hint),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    actionLabel: String,
    showAction: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, modifier = Modifier.padding(top = 12.dp))
        if (showAction) {
            TextButton(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}
