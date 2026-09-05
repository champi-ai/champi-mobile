package ai.champi.app

import ai.champi.core.context.ContextSettingsRepository
import ai.champi.core.overlay.OverlayPreferencesRepository
import ai.champi.core.overlay.QuickActionGeometry
import ai.champi.core.persistence.ModelFileStore
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.providers.api.Locality
import ai.champi.providers.api.LlmProvider
import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.TtsProvider
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Plain (non-overlay) settings screen, launched from the quick-actions Settings target via an
 * explicit intent naming [ai.champi.core.overlay.SETTINGS_ACTIVITY_CLASS]. Phase 4 extends this
 * screen (first built in issue #13) with a provider pipeline section: edge-only master toggle and
 * per-provider enable/disable rows for the STT, LLM, and TTS stages.
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    @Inject lateinit var preferences: OverlayPreferencesRepository
    @Inject lateinit var routingSettings: RoutingSettingsRepository
    @Inject lateinit var contextSettings: ContextSettingsRepository
    @Inject lateinit var llmProvider: LlmProvider
    @Inject lateinit var sttProvider: SttProvider
    @Inject lateinit var ttsProvider: TtsProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        preferences = preferences,
                        routingSettings = routingSettings,
                        contextSettings = contextSettings,
                        sttProviders = listOf(sttProvider),
                        llmProviders = listOf(llmProvider),
                        ttsProviders = listOf(ttsProvider),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    preferences: OverlayPreferencesRepository,
    routingSettings: RoutingSettingsRepository,
    contextSettings: ContextSettingsRepository,
    sttProviders: List<SttProvider>,
    llmProviders: List<LlmProvider>,
    ttsProviders: List<TtsProvider>,
) {
    val scope = rememberCoroutineScope()
    val geometry by preferences.quickActionGeometry.collectAsState(initial = QuickActionGeometry.RADIAL_ARC)
    val peekMinutes by preferences.peekMinutes.collectAsState(initial = 5)
    val edgeOnly by routingSettings.edgeOnlyMode.collectAsState(initial = false)

    val providerRows = remember(sttProviders, llmProviders, ttsProviders) {
        buildProviderRows(sttProviders, llmProviders, ttsProviders)
    }

    // rememberScrollState() uses rememberSaveable internally, so scroll position survives rotation.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text(
            "Quick-actions layout",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Column(modifier = Modifier.selectableGroup().padding(top = 8.dp)) {
            QuickActionGeometry.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = geometry == option,
                            onClick = { scope.launch { preferences.setQuickActionGeometry(option) } },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = geometry == option, onClick = null)
                    Text(
                        when (option) {
                            QuickActionGeometry.RADIAL_ARC -> "Radial arc"
                            QuickActionGeometry.EDGE_RAIL -> "Edge rail"
                        },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }

        Text(
            "Bubble peek",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            if (peekMinutes == 0) {
                "Disabled — the bubble stays fully visible"
            } else {
                "Tucks under the edge after $peekMinutes ${if (peekMinutes == 1) "minute" else "minutes"} idle"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Slider(
            value = peekMinutes.toFloat(),
            onValueChange = { scope.launch { preferences.setPeekMinutes(it.toInt()) } },
            valueRange = 0f..15f,
            steps = 14,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics { contentDescription = "Bubble peek idle timeout in minutes, 0 disables" },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        ProvidersSection(
            edgeOnly = edgeOnly,
            onEdgeOnlyChange = { scope.launch { routingSettings.setEdgeOnlyMode(it) } },
            providerRows = providerRows,
            routingSettings = routingSettings,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        ContextSection(contextSettings = contextSettings)
    }
}

@Composable
private fun ProvidersSection(
    edgeOnly: Boolean,
    onEdgeOnlyChange: (Boolean) -> Unit,
    providerRows: List<ProviderRowModel>,
    routingSettings: RoutingSettingsRepository,
) {
    val scope = rememberCoroutineScope()

    Text("Providers", style = MaterialTheme.typography.headlineSmall)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Edge-only mode", style = MaterialTheme.typography.titleMedium)
            Text(
                "Remote providers are never selected regardless of input size",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = edgeOnly,
            onCheckedChange = onEdgeOnlyChange,
            modifier = Modifier
                .padding(start = 16.dp)
                .semantics { contentDescription = "Edge-only mode toggle" },
        )
    }

    val groupedRows = remember(providerRows) { providerRows.groupBy { it.stage } }
    listOf(ProviderStage.STT, ProviderStage.LLM, ProviderStage.TTS).forEach { stage ->
        val rows = groupedRows[stage] ?: return@forEach

        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
        Text(
            when (stage) {
                ProviderStage.STT -> "Speech recognition"
                ProviderStage.LLM -> "Language model"
                ProviderStage.TTS -> "Text to speech"
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        rows.forEach { row ->
            val enabledFlow: Flow<Boolean> = rememberEnabledFlow(row.stage, row.locality, routingSettings)
            val enabled by enabledFlow.collectAsState(initial = true)
            ProviderRow(
                row = row,
                enabled = enabled,
                onEnabledChange = { scope.launch { setEnabledFor(row.stage, row.locality, it, routingSettings) } },
            )
        }
    }
}

@Composable
private fun ProviderRow(
    row: ProviderRowModel,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val modelDir = remember(row.id) { File(context.filesDir, "models/${row.id}") }
    var modelExists by remember { mutableStateOf(modelDir.exists() && modelDir.list()?.isNotEmpty() == true) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.id, style = MaterialTheme.typography.bodyMedium)
            Text(
                row.localityLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (row.locality == Locality.EDGE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (modelExists) {
            TextButton(
                onClick = {
                    scope.launch {
                        val store = ModelFileStore(context, row.id, "current")
                        if (store.delete()) modelExists = false
                    }
                },
            ) {
                Text("Delete model")
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier
                .padding(start = 8.dp)
                .semantics {
                    contentDescription = "${row.localityLabel} ${row.stage.name.lowercase()} provider toggle"
                },
        )
    }
}

@Composable
private fun rememberEnabledFlow(
    stage: ProviderStage,
    locality: Locality,
    routingSettings: RoutingSettingsRepository,
): Flow<Boolean> = remember(stage, locality) {
    when (stage) {
        ProviderStage.STT -> if (locality == Locality.EDGE) routingSettings.edgeSttEnabled else routingSettings.remoteSttEnabled
        ProviderStage.LLM -> if (locality == Locality.EDGE) routingSettings.edgeLlmEnabled else routingSettings.remoteLlmEnabled
        ProviderStage.TTS -> if (locality == Locality.EDGE) routingSettings.edgeTtsEnabled else routingSettings.remoteTtsEnabled
    }
}

private suspend fun setEnabledFor(
    stage: ProviderStage,
    locality: Locality,
    enabled: Boolean,
    routingSettings: RoutingSettingsRepository,
) {
    when (stage) {
        ProviderStage.STT -> if (locality == Locality.EDGE) routingSettings.setEdgeSttEnabled(enabled) else routingSettings.setRemoteSttEnabled(enabled)
        ProviderStage.LLM -> if (locality == Locality.EDGE) routingSettings.setEdgeLlmEnabled(enabled) else routingSettings.setRemoteLlmEnabled(enabled)
        ProviderStage.TTS -> if (locality == Locality.EDGE) routingSettings.setEdgeTtsEnabled(enabled) else routingSettings.setRemoteTtsEnabled(enabled)
    }
}

/**
 * Renders the optional context-signal toggle section. Each of the four signals maps to a
 * DataStore-backed boolean in [ContextSettingsRepository].
 *
 * Location toggle: enabling it triggers a [Manifest.permission.ACCESS_COARSE_LOCATION] runtime
 * permission request. If the user denies the request the toggle reverts to off.
 *
 * Foreground-app toggle: [android.app.AppOpsManager.OPSTR_GET_USAGE_STATS] is a special AppOps
 * permission that cannot be requested via the standard runtime-permission dialog. When the user
 * enables this toggle, a button opens [Settings.ACTION_USAGE_ACCESS_SETTINGS] so they can grant
 * it manually; the toggle remains on regardless of grant state — [PeriodicContextProvider]
 * checks the permission before every read and silently skips the signal if not granted.
 */
@Composable
private fun ContextSection(contextSettings: ContextSettingsRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val locationEnabled by contextSettings.locationContextEnabled.collectAsState(initial = false)
    val batteryEnabled by contextSettings.batteryContextEnabled.collectAsState(initial = false)
    val connectivityEnabled by contextSettings.connectivityContextEnabled.collectAsState(initial = false)
    val foregroundAppEnabled by contextSettings.foregroundAppContextEnabled.collectAsState(initial = false)

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            scope.launch { contextSettings.setLocationContextEnabled(false) }
        }
    }

    Text("Context signals", style = MaterialTheme.typography.headlineSmall)
    Text(
        "All signals are opt-in and off by default. Enabled signals are injected as ambient " +
            "context into each conversation turn, not stored in conversation history.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    ContextToggleRow(
        label = "Location",
        description = "Prepends coarse location to each turn. Requires location permission.",
        checked = locationEnabled,
        onCheckedChange = { enabled ->
            if (enabled) {
                val already = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
                if (already) {
                    scope.launch { contextSettings.setLocationContextEnabled(true) }
                } else {
                    scope.launch { contextSettings.setLocationContextEnabled(true) }
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            } else {
                scope.launch { contextSettings.setLocationContextEnabled(false) }
            }
        },
        contentDesc = "Location context toggle",
    )

    ContextToggleRow(
        label = "Battery",
        description = "Prepends battery level and charging status to each turn.",
        checked = batteryEnabled,
        onCheckedChange = { scope.launch { contextSettings.setBatteryContextEnabled(it) } },
        contentDesc = "Battery context toggle",
    )

    ContextToggleRow(
        label = "Connectivity",
        description = "Prepends active network type (WiFi, mobile, etc.) to each turn.",
        checked = connectivityEnabled,
        onCheckedChange = { scope.launch { contextSettings.setConnectivityContextEnabled(it) } },
        contentDesc = "Connectivity context toggle",
    )

    ContextToggleRow(
        label = "Foreground app",
        description = "Prepends the most recently used app to each turn. Requires usage access " +
            "(a special permission granted via system settings, not a standard dialog).",
        checked = foregroundAppEnabled,
        onCheckedChange = { enabled ->
            scope.launch { contextSettings.setForegroundAppContextEnabled(enabled) }
            if (enabled) {
                // PACKAGE_USAGE_STATS is not a normal runtime permission — direct the user to the
                // system Usage Access settings screen where they can grant it manually.
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        },
        contentDesc = "Foreground app context toggle",
    )
}

@Composable
private fun ContextToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDesc: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .padding(start = 16.dp)
                .semantics { contentDescription = contentDesc },
        )
    }
}
