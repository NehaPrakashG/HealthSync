package dev.neha.healthsync.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful entry point: owns the ViewModel, activity-result launchers and side effects.
 * [DashboardScreen] stays stateless and previewable.
 */
@Composable
fun DashboardRoute(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> viewModel.onPermissionResult(granted) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DashboardEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    DashboardScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = DashboardActions(
            onConnect = { feature -> permissionLauncher.launch(viewModel.permissionRequestFor(feature)) },
            onRequestExtendedAccess = { permissionLauncher.launch(viewModel.extendedAccessRequest()) },
            onOpenHealthConnectSettings = { context.openHealthConnectSettings() },
            onInstallHealthConnect = { context.openHealthConnectInPlayStore() },
            onLogSteps = { viewModel.logSteps() },
            onSimulateWatch = { viewModel.simulateWatchReading() },
            onSyncNow = { viewModel.syncNow() },
            onRetry = { viewModel.refresh() },
        ),
    )
}

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

private fun Context.openHealthConnectSettings() {
    runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
}

private fun Context.openHealthConnectInPlayStore() {
    val uri = "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri()
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.android.vending")
        putExtra("overlay", true)
        putExtra("callerId", packageName)
    }
    runCatching { startActivity(intent) }
}
