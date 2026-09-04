package ai.champi.actions

import ai.champi.providers.api.ToolCall
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Grant "Calendar" (READ_CALENDAR + WRITE_CALENDAR) for ai.champi.actions.test to exercise
 * [CalendarActionProvider.insertDirectly] against a real provider on-device; without it, that test
 * is skipped rather than asserting a false negative — CI/emulator runs won't have the grant.
 */
@RunWith(AndroidJUnit4::class)
class CalendarActionProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = CalendarActionProvider(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val hasCalendarPermission: Boolean
        get() = context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @Test
    fun insertDirectlyCreatesARealCalendarEvent() {
        if (!hasCalendarPermission) return // no device calendar account to write into in this run

        val start = System.currentTimeMillis() + 3_600_000L
        val result = provider.insertDirectly(
            ToolCall(id = "1", name = "create_event", argumentsJson = "{}"),
            CreateEventArgs(title = "champi-mobile instrumented test event", startEpochMs = start, durationMinutes = 30),
        )

        assertFalse("expected success, got: ${result.resultJson}", result.isError)
        val payload = json.parseToJsonElement(result.resultJson).jsonObject
        assertEquals("created", payload["status"]?.jsonPrimitive?.content)
        val eventId = payload["eventId"]?.jsonPrimitive?.long ?: error("missing eventId")
        assertTrue(eventId > 0)

        // This writes into whatever real calendar account is on the device (there's no sandboxed
        // calendar provider), so clean up immediately rather than leaving test data behind.
        val deleted = context.contentResolver.delete(
            android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, eventId),
            null,
            null,
        )
        assertEquals(1, deleted)
    }

    @Test
    fun invalidArgumentsReturnAnErrorInsteadOfThrowing() = runBlocking {
        val result = provider.invoke(
            ToolCall(id = "2", name = "create_event", argumentsJson = """{"title":"","startEpochMs":0,"durationMinutes":30}"""),
        )
        assertTrue(result.isError)
    }

    @Test
    fun malformedJsonReturnsAnErrorInsteadOfThrowing() = runBlocking {
        val result = provider.invoke(
            ToolCall(id = "3", name = "create_event", argumentsJson = "not json"),
        )
        assertTrue(result.isError)
    }

    @Test
    fun zeroDurationIsRejected() = runBlocking {
        val result = provider.invoke(
            ToolCall(id = "4", name = "create_event", argumentsJson = """{"title":"x","startEpochMs":0,"durationMinutes":0}"""),
        )
        assertTrue(result.isError)
    }

    @Test
    fun specsExposeCreateEvent() {
        assertTrue(provider.specs.any { it.name == "create_event" })
    }
}
