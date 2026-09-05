package ai.champi.overlay

import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.ConfirmationRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Full-surface scrim + dialog shown when [AppStateHolder.pendingConfirmation] is non-null.
 * Approve/Decline buttons call [AppStateHolder.respondToConfirmation] to resume the
 * [TurnOrchestrator] coroutine that is suspended waiting for the user's response.
 */
@Composable
internal fun ConfirmationDialog(
    request: ConfirmationRequest,
    appStateHolder: AppStateHolder,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.60f))
            .semantics { contentDescription = "Action confirmation dialog" },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = request.toolName.replace('_', ' ').replaceFirstChar { it.uppercaseChar() },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = request.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = { appStateHolder.respondToConfirmation(approved = false) },
                        modifier = Modifier.semantics { contentDescription = "Decline" },
                    ) {
                        Text("Decline")
                    }
                    Button(
                        onClick = { appStateHolder.respondToConfirmation(approved = true) },
                        modifier = Modifier.semantics { contentDescription = "Allow" },
                    ) {
                        Text("Allow")
                    }
                }
            }
        }
    }
}
