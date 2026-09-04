package ai.champi.providers.remote

import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Conversation
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.LlmEvent
import ai.champi.providers.api.LlmProvider
import ai.champi.providers.api.Locality
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionRequest(val model: String, val messages: List<ChatMessage>, val stream: Boolean = false)

@Serializable
private data class ChatCompletionResponse(val choices: List<Choice> = emptyList())

@Serializable
private data class Choice(val message: ChatMessage = ChatMessage("assistant", ""))

/**
 * Talks to any OpenAI-compatible `/chat/completions` endpoint (Ollama's OpenAI-compat layer,
 * LM Studio, OpenRouter, etc.) — configured via [BuildConfig], sourced from `local.properties`
 * so no endpoint/key is committed to the repo.
 *
 * Requests `stream: false` and emits the whole reply as a single [LlmEvent.Token]: some
 * OpenAI-compatible servers (this one included — a cloud-routed Ollama model) return a full
 * `chat.completion` JSON body with `Content-Type: application/json` even when `stream: true` is
 * requested, which a strict SSE client (`okhttp-sse`) rejects outright since it isn't
 * `text/event-stream`. True incremental streaming can be revisited once the target backend is
 * confirmed to honor it. Tool-calling isn't wired yet (issue #40); `tools` is accepted but ignored.
 */
@Singleton
class RemoteLlmProvider @Inject constructor() : LlmProvider {
    override val id = "remote-openai-compatible"
    override val locality = Locality.REMOTE
    override val cost = Cost(LatencyClass.MEDIUM, BatteryClass.LOW)
    override val capabilities = ProviderCapabilities(
        languages = listOf("en", "es"),
        maxInputTokens = 8192,
        supportsStreaming = false,
    )

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun available(): Boolean =
        BuildConfig.LLM_BASE_URL.isNotBlank() && BuildConfig.LLM_MODEL.isNotBlank()

    override fun complete(ctx: Conversation, tools: List<ToolSpec>): Flow<LlmEvent> = flow {
        val requestJson = json.encodeToString(
            ChatCompletionRequest(
                model = BuildConfig.LLM_MODEL,
                messages = ctx.turns.map { ChatMessage(role = it.role.name.lowercase(), content = it.text) },
            ),
        )
        val request = Request.Builder()
            .url("${BuildConfig.LLM_BASE_URL}/chat/completions")
            .addHeader("Authorization", "Bearer ${BuildConfig.LLM_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        val body = executeAsync(request)
        val reply = json.decodeFromString<ChatCompletionResponse>(body).choices.firstOrNull()?.message?.content.orEmpty()
        if (reply.isNotEmpty()) emit(LlmEvent.Token(reply))
        emit(LlmEvent.Done("stop"))
    }.flowOn(Dispatchers.IO)

    private suspend fun executeAsync(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        continuation.resumeWithException(IOException("HTTP ${it.code}: ${it.body?.string()}"))
                        return
                    }
                    continuation.resumeWith(Result.success(it.body?.string().orEmpty()))
                }
            }
        })
    }
}
