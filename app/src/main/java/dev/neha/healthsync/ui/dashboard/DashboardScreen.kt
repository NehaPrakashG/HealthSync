package dev.neha.healthsync.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.neha.healthsync.domain.model.DailySteps
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.domain.model.StepsSummary
import dev.neha.healthsync.domain.model.SyncStatus
import dev.neha.healthsync.ui.theme.HealthSyncTheme
import java.time.Instant
import java.time.LocalDate

/** All user intents, so the screen has one parameter for callbacks. */
data class DashboardActions(
    val onConnect: (HealthFeature) -> Unit = {},
    val onRequestExtendedAccess: () -> Unit = {},
    val onOpenHealthConnectSettings: () -> Unit = {},
    val onInstallHealthConnect: () -> Unit = {},
    val onLogSteps: () -> Unit = {},
    val onSimulateWatch: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    snackbarHostState: SnackbarHostState,
    actions: DashboardActions,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("HealthSync") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (state.availability) {
                AvailabilityUi.Checking -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                AvailabilityUi.NotSupported -> InfoPanel(
                    title = "Health Connect isn't supported",
                    body = "This device can't run Health Connect, so health features are unavailable.",
                )

                AvailabilityUi.UpdateRequired -> InfoPanel(
                    title = "Health Connect needed",
                    body = "Install or update Health Connect from the Play Store to continue.",
                    actionLabel = "Get Health Connect",
                    onAction = actions.onInstallHealthConnect,
                )

                AvailabilityUi.Available -> DashboardContent(state, actions)
            }
        }
    }
}

@Composable
private fun DashboardContent(state: DashboardUiState, actions: DashboardActions) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FeatureCard(
                title = "Steps",
                rationale = "See your daily steps and weekly trend.",
                state = state.steps,
                onConnect = { actions.onConnect(HealthFeature.STEPS) },
                onOpenSettings = actions.onOpenHealthConnectSettings,
                onRetry = actions.onRetry,
            ) { StepsContent(it, actions.onLogSteps) }
        }
        item {
            FeatureCard(
                title = "Heart rate",
                rationale = "Track your heart rate from your watch.",
                state = state.heartRate,
                onConnect = { actions.onConnect(HealthFeature.HEART_RATE) },
                onOpenSettings = actions.onOpenHealthConnectSettings,
                onRetry = actions.onRetry,
            ) { HeartRateContent(it, actions.onSimulateWatch) }
        }
        item { ExtendedAccessCard(state.access, actions.onRequestExtendedAccess) }
        item { SyncCard(state.sync, state.isSyncing, actions.onSyncNow) }
    }
}

@Composable
private fun InfoPanel(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, textAlign = TextAlign.Center)
        if (actionLabel != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

// ------------------------------------------------------------------ Previews

@Preview(showBackground = true)
@Composable
private fun DashboardPreview() {
    val today = LocalDate.of(2026, 11, 12)
    HealthSyncTheme {
        DashboardScreen(
            state = DashboardUiState(
                availability = AvailabilityUi.Available,
                steps = FeatureUi.Ready(
                    StepsSummary(
                        today = 6_420,
                        last7Days = (6L downTo 0L).map { DailySteps(today.minusDays(it), 4_000L + it * 900) },
                    )
                ),
                heartRate = FeatureUi.NotGranted(deniedTwice = false),
                access = AccessUi(backgroundSupported = true),
                sync = SyncStatus(lastSyncAt = Instant.parse("2026-11-12T18:00:00Z"), pendingUploads = 3, uploadedRecords = 42),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            actions = DashboardActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UpdateRequiredPreview() {
    HealthSyncTheme {
        DashboardScreen(
            state = DashboardUiState(availability = AvailabilityUi.UpdateRequired),
            snackbarHostState = remember { SnackbarHostState() },
            actions = DashboardActions(),
        )
    }
}
