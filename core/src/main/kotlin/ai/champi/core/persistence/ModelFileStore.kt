package ai.champi.core.persistence

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Resolves a versioned on-disk path for a downloadable model (STT/TTS/wake-word/LLM weights)
 * under [Context.filesDir] — no external storage permission needed. Download logic belongs in
 * `:providers:edge`; this is just where a downloaded model lives once it's there.
 */
class ModelFileStore(context: Context, modelId: String, version: String) {
    val path: File = File(File(context.filesDir, "models/$modelId"), version)

    fun exists(): Boolean = path.exists()

    fun inputStream(): InputStream = path.inputStream()

    fun delete(): Boolean = !path.exists() || path.deleteRecursively()
}
