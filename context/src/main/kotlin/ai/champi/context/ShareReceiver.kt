package ai.champi.context

import ai.champi.assistant.ConversationManager
import ai.champi.core.conversation.AttachmentType
import ai.champi.core.state.AppStateHolder
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Transparent, no-history entry point for the Android share sheet.
 *
 * Handles [Intent.ACTION_SEND] and [Intent.ACTION_SEND_MULTIPLE]. For image and file URIs the
 * content is copied into the app's cache directory before the Activity finishes, so the message
 * remains accessible even after the sharing app's content provider revokes the URI grant.
 *
 * On [Intent.ACTION_SEND_MULTIPLE], only the first URI in the list is processed to keep the
 * initial implementation testable without batching complexity.
 */
@AndroidEntryPoint
class ShareReceiver : ComponentActivity() {

    @Inject lateinit var conversationManager: ConversationManager
    @Inject lateinit var appStateHolder: AppStateHolder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_SEND -> {
                val mimeType = intent.type ?: return
                when {
                    mimeType.startsWith("text/") -> handleText(intent)
                    else -> handleUri(getSingleUri(intent), mimeType)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val mimeType = intent.type ?: return
                val uris = getMultipleUris(intent)
                val first = uris.firstOrNull() ?: return
                handleUri(first, mimeType)
            }
        }
    }

    private fun handleText(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        scope.launch {
            conversationManager.appendUserMessage(text)
            appStateHolder.requestOpenPanel()
        }
    }

    private fun handleUri(uri: Uri?, mimeType: String) {
        if (uri == null) return

        tryTakePersistablePermission(uri)

        val attachmentType = attachmentTypeForMime(mimeType)
        val extension = extensionForMime(mimeType)
        val originalName = uri.lastPathSegment?.let { File(it).name } ?: "shared$extension"

        scope.launch {
            val cachedPath = runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    writeToCacheAndEvict(this@ShareReceiver, stream, originalName)
                }
            }.getOrNull()

            val messageText = when (attachmentType) {
                AttachmentType.IMAGE -> "[image]"
                AttachmentType.FILE -> originalName
            }

            conversationManager.appendUserMessage(
                text = messageText,
                attachmentUri = cachedPath,
                attachmentType = if (cachedPath != null) attachmentType else null,
            )
            appStateHolder.requestOpenPanel()
        }
    }

    private fun tryTakePersistablePermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Not all content providers support persistable permissions. The failure is non-fatal
        // because the content is copied to cache before the Activity finishes.
    }

    private fun getSingleUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun getMultipleUris(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
        }
}

/**
 * Maps a MIME type to an [AttachmentType]. Anything under the image MIME family is an image;
 * everything else (including application/pdf and wildcard types) is treated as a generic file.
 */
internal fun attachmentTypeForMime(mimeType: String): AttachmentType =
    if (mimeType.startsWith("image/")) AttachmentType.IMAGE else AttachmentType.FILE

/** Returns a dot-prefixed file extension for common MIME types, or an empty string otherwise. */
internal fun extensionForMime(mimeType: String): String = when (mimeType) {
    "image/jpeg" -> ".jpg"
    "image/png" -> ".png"
    "image/gif" -> ".gif"
    "image/webp" -> ".webp"
    "application/pdf" -> ".pdf"
    "text/plain" -> ".txt"
    "text/html" -> ".html"
    else -> ""
}
