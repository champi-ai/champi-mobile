package ai.champi.app

import ai.champi.core.overlay.OverlayPreferencesRepository
import ai.champi.core.overlay.QuickActionGeometry
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Plain (non-overlay) settings screen, launched from the quick-actions Settings target via an
 * explicit intent naming [ai.champi.core.overlay.SETTINGS_ACTIVITY_CLASS]. Phase 1: just the two
 * persisted overlay preferences that exist so far — quick-actions geometry and peek idle timeout.
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    @Inject lateinit var preferences: OverlayPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(preferences)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(preferences: OverlayPreferencesRepository) {
    val scope = rememberCoroutineScope()
    val geometry by preferences.quickActionGeometry.collectAsState(initial = QuickActionGeometry.RADIAL_ARC)
    val peekMinutes by preferences.peekMinutes.collectAsState(initial = 5)

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
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
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
