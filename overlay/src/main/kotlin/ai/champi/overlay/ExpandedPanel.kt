package ai.champi.overlay

import ai.champi.assistant.ConversationManager
import ai.champi.assistant.TurnOrchestrator
import ai.champi.core.conversation.AttachmentType
import ai.champi.core.state.AppState
import ai.champi.core.state.CharacterState
import ai.champi.core.state.ConversationEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.io.File

private const val SWIPE_DOWN_DISMISS_THRESHOLD_DP = 48

/**
 * Conversation panel shown when the bubble is tapped: header with the character state tag,
 * message list, and the text input row.
 */
@Composable
fun ExpandedPanel(
    appState: AppState,
    conversationManager: ConversationManager,
    turnOrchestrator: TurnOrchestrator,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dismissThresholdPx = with(density) { SWIPE_DOWN_DISMISS_THRESHOLD_DP.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background layer for the tap/swipe-to-collapse convenience gesture, sitting behind
            // the real content below. Keeping this a separate layer (rather than wrapping the
            // whole Column in .clickable) matters now that this panel has real interactive
            // controls in it: an ancestor .clickable merges all *non-interactive* descendant
            // semantics into one giant TalkBack node, which would swallow plain Text/Icon content
            // even though a genuinely interactive descendant survives the merge — better not to
            // rely on that distinction at all. Excluded from the semantics tree since the
            // dedicated "Close panel" handle below already exposes an accessible equivalent.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onCollapse() }
                    .pointerInput(Unit) {
                        var totalDragY = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDragY = 0f },
                            onDragEnd = { if (totalDragY > dismissThresholdPx) onCollapse() },
                        ) { change, dragAmount ->
                            change.consume()
                            totalDragY += dragAmount
                        }
                    }
                    .clearAndSetSemantics {},
            )

            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onCollapse() }
                        .semantics { contentDescription = "Close panel" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CharacterPlaceholder(state = appState.characterState, size = 96.dp)
                    Column {
                        Text("Champi", style = MaterialTheme.typography.titleMedium)
                        // Reserves space regardless of label length so the header doesn't shift
                        // between e.g. "thinking" and "idle".
                        Text(
                            characterStateLabel(appState.characterState),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                if (appState.conversation.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No conversation yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    // Auto-scroll to the newest message only if the user was already at (or near)
                    // the bottom — otherwise a user scrolling back through history would get
                    // yanked back down on every streamed token.
                    LaunchedEffect(appState.conversation.size, appState.conversation.lastOrNull()?.text) {
                        val wasNearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { lastVisible ->
                            lastVisible >= appState.conversation.size - 2
                        } ?: true
                        if (wasNearBottom) {
                            listState.animateScrollToItem((appState.conversation.size - 1).coerceAtLeast(0))
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f), state = listState) {
                        items(appState.conversation, key = { it.id }) { entry ->
                            MessageBubble(entry = entry)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                InputRow(
                    enabled = appState.characterState == CharacterState.IDLE,
                    onSend = { text -> coroutineScope.launch { turnOrchestrator.submitText(text) } },
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(entry: ConversationEntry) {
    val fromUser = entry.fromUser
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 260.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (entry.attachmentType) {
                    AttachmentType.IMAGE -> {
                        val context = LocalContext.current
                        val model = entry.attachmentUri?.let {
                            ImageRequest.Builder(context).data(File(it)).build()
                        }
                        if (model != null) {
                            AsyncImage(
                                model = model,
                                contentDescription = "Shared image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    AttachmentType.FILE -> {
                        val fileName = entry.attachmentUri?.let { File(it).name } ?: "file"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(fileName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    null -> Unit
                }
                if (entry.text.isNotEmpty()) {
                    Text(entry.text)
                }
            }
        }
    }
}

@Composable
private fun InputRow(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (text.isNotBlank() && enabled) {
                        onSend(text)
                        text = ""
                    }
                },
            ),
        )
        IconButton(
            enabled = enabled && text.isNotBlank(),
            onClick = {
                onSend(text)
                text = ""
            },
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

private fun characterStateLabel(state: CharacterState): String = when (state) {
    CharacterState.IDLE -> "idle"
    CharacterState.LISTENING -> "listening"
    CharacterState.THINKING -> "thinking"
    CharacterState.SPEAKING -> "speaking"
    CharacterState.NOTIFYING -> "notifying"
    CharacterState.ERROR -> "error"
    CharacterState.SLEEPING -> "sleeping"
}
