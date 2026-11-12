package dev.neha.healthsync.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.neha.healthsync.domain.model.DailySteps
import dev.neha.healthsync.domain.model.HeartRateSummary
import dev.neha.healthsync.domain.model.StepsSummary
import dev.neha.healthsync.domain.model.SyncStatus
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ----------------------------------------------------------- Generic feature card

@Composable
fun <T> FeatureCard(
    title: String,
    rationale: String,
    state: FeatureUi<T>,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when (state) {
                FeatureUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())

                is FeatureUi.NotGranted -> {
                    Text(rationale, style = MaterialTheme.typography.bodyMedium)
                    if (state.deniedTwice) {
                        // Android no longer shows the dialog after two denials.
                        Text(
                            "Access was declined. Turn it on in Health Connect settings.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onOpenSettings) { Text("Open Health Connect settings") }
                    } else {
                        Button(onClick = onConnect) { Text("Connect") }
                    }
                }

                is FeatureUi.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }

                is FeatureUi.Ready -> content(state.data)
            }
        }
    }
}

// -------------------------------------------------------------------- Steps

@Composable
fun StepsContent(summary: StepsSummary, onLogSteps: () -> Unit) {
    Text(
        text = NumberFormat.getIntegerInstance().format(summary.today),
        style = MaterialTheme.typography.displaySmall,
    )
    Text("steps today", style = MaterialTheme.typography.bodySmall)
    WeekBars(summary.last7Days)
    OutlinedButton(onClick = onLogSteps) { Text("Log 500 steps") }
}

@Composable
private fun WeekBars(days: List<DailySteps>) {
    val max = (days.maxOfOrNull { it.count } ?: 0L).coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            val label = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "$label: ${day.count} steps" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val barHeight = (90f * day.count / max).coerceAtLeast(2f)
                Box(
                    Modifier
                        .width(18.dp)
                        .height(barHeight.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                )
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// --------------------------------------------------------------- Heart rate

@Composable
fun HeartRateContent(summary: HeartRateSummary, onSimulateWatch: () -> Unit) {
    if (summary.latestBpm == null) {
        Text("No readings today", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("${summary.latestBpm} bpm", style = MaterialTheme.typography.displaySmall)
        summary.latestAt?.let { Text("Latest at ${it.format("h:mm a")}", style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("Avg", summary.avgBpm)
            Stat("Min", summary.minBpm)
            Stat("Max", summary.maxBpm)
        }
    }
    OutlinedButton(onClick = onSimulateWatch) { Text("Simulate watch reading") }
}

@Composable
private fun Stat(label: String, value: Long?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value?.toString() ?: "–", style = MaterialTheme.typography.titleMedium)
    }
}

// ---------------------------------------------------------- Extended access

@Composable
fun ExtendedAccessCard(access: AccessUi, onRequest: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Background & history", style = MaterialTheme.typography.titleMedium)
            if (!access.backgroundSupported) {
                Text(
                    "Background sync needs a newer Health Connect version. Data syncs when you open the app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Background sync: ${if (access.backgroundGranted) "On" else "Off"}")
            }
            Text("Data older than 30 days: ${if (access.historyGranted) "On" else "Off"}")
            if (!access.historyGranted || (access.backgroundSupported && !access.backgroundGranted)) {
                OutlinedButton(onClick = onRequest) { Text("Enable extended access") }
            }
        }
    }
}

// --------------------------------------------------------------------- Sync

@Composable
fun SyncCard(status: SyncStatus, isSyncing: Boolean, onSyncNow: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backend sync", style = MaterialTheme.typography.titleMedium)
            Text("Last sync: ${status.lastSyncAt?.format("MMM d, h:mm a") ?: "Never"}")
            Text("Pending upload: ${status.pendingUploads}   Uploaded: ${status.uploadedRecords}")
            status.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSyncNow, enabled = !isSyncing) {
                if (isSyncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Sync now")
                }
            }
        }
    }
}

private fun Instant.format(pattern: String): String =
    DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(this)
