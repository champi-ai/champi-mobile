package ai.champi.providers.api

import kotlinx.serialization.Serializable

/** A tool the LLM can call, advertised to it via [LlmProvider.complete]'s `tools` parameter. */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema for the call's arguments, as a string rather than a typed schema model. */
    val parametersJsonSchema: String,
    /**
     * When `true`, the orchestrator must present a confirmation dialog to the user before calling
     * [ActionProvider.invoke]. Intended for actions that are difficult to undo (e.g. direct
     * calendar inserts, SMS sends) per §2.6 of the spec.
     */
    val requiresConfirmation: Boolean = false,
)

/** One invocation of a [ToolSpec] the LLM emitted mid-response (see [LlmEvent.ToolCallEvent]). */
@Serializable
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

@Serializable
data class ToolResult(val callId: String, val resultJson: String, val isError: Boolean = false)

/**
 * Device-local capabilities (alarms, calendar, etc.) exposed as tools. Deliberately not a
 * [Provider]: actions aren't edge/remote-routable the same way the pipeline stages are.
 */
interface ActionProvider {
    val specs: List<ToolSpec>
    suspend fun invoke(call: ToolCall): ToolResult
}
