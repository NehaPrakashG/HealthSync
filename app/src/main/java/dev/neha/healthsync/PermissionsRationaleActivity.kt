package dev.neha.healthsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.neha.healthsync.ui.theme.HealthSyncTheme

/**
 * Privacy policy shown by Health Connect from the permission screen.
 * Required: without this activity the permission dialog does not appear.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthSyncTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("How HealthSync uses your data", style = MaterialTheme.typography.headlineSmall)
                        Text("• Steps and heart rate are read to show your daily totals and trends.")
                        Text("• Steps you log and heart-rate readings from your watch are written to Health Connect so other apps can use them.")
                        Text("• Background access lets HealthSync keep your data in sync while the app is closed.")
                        Text("• History access lets HealthSync import data older than 30 days.")
                        Text("• Data is uploaded only to HealthSync's own servers, encrypted in transit. It is never sold or shared with advertisers.")
                        Text("• You can revoke any permission at any time in Health Connect settings.")
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
