package ai.champi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionScreen(onAllGranted = ::startChampiService)
                }
            }
        }
    }

    private fun startChampiService() {
        ContextCompat.startForegroundService(this, Intent(this, ChampiService::class.java))
    }
}

private fun notificationsGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionScreen(onAllGranted: () -> Unit) {
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifGranted by remember { mutableStateOf(notificationsGranted(context)) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notifGranted = granted }

    LaunchedEffect(overlayGranted, notifGranted) {
        if (overlayGranted && notifGranted) onAllGranted()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Champi needs a couple of permissions to show its overlay.")

        if (!overlayGranted) {
            Button(onClick = {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }) { Text("Grant overlay permission") }
        }

        if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Button(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Allow notifications")
            }
        }

        if (overlayGranted && notifGranted) {
            Text("All set — Champi is running.")
        }
    }
}
