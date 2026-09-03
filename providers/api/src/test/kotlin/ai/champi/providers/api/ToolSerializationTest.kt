package ai.champi.providers.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Acceptance criterion for issue #17: ToolSpec/ToolCall/ToolResult round-trip through JSON. */
class ToolSerializationTest {

    private val json = Json

    @Test
    fun toolSpecRoundTrips() {
        val spec = ToolSpec(
            name = "set_alarm",
            description = "Sets a device alarm",
            parametersJsonSchema = """{"type":"object","properties":{"time":{"type":"string"}}}""",
        )
        assertEquals(spec, json.decodeFromString<ToolSpec>(json.encodeToString(spec)))
    }

    @Test
    fun toolCallRoundTrips() {
        val call = ToolCall(id = "call_1", name = "set_alarm", argumentsJson = """{"time":"07:00"}""")
        assertEquals(call, json.decodeFromString<ToolCall>(json.encodeToString(call)))
    }

    @Test
    fun toolResultRoundTrips() {
        val result = ToolResult(callId = "call_1", resultJson = """{"ok":true}""", isError = false)
        assertEquals(result, json.decodeFromString<ToolResult>(json.encodeToString(result)))
    }

    @Test
    fun toolResultRoundTripsWithError() {
        val result = ToolResult(callId = "call_2", resultJson = """{"message":"not found"}""", isError = true)
        assertEquals(result, json.decodeFromString<ToolResult>(json.encodeToString(result)))
    }
}
