package ai.champi.actions

import ai.champi.providers.api.ToolCall
import android.app.AlarmManager
import android.os.Build
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
 * Toggling SCHEDULE_EXACT_ALARM via `appops`/`pm` against this test's own live process kills it
 * (signal 9) the same way revoking RECORD_AUDIO did for :audio's instrumented tests — so instead
 * of mutating the grant, these tests read the device's real [AlarmManager.canScheduleExactAlarms]
 * state and assert whichever branch of [AlarmTimerActionProvider.invoke] that state implies.
 * Grant "Alarms & reminders" for ai.champi.actions.test manually to exercise the success path.
 */
@RunWith(AndroidJUnit4::class)
class AlarmTimerActionProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = AlarmTimerActionProvider(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val exactAlarmsAllowed: Boolean
        get() {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        }

    @Test
    fun setTimerSchedulesRealAlarmInTheNearFuture() = runBlocking {
        val before = System.currentTimeMillis()
        val result = provider.invoke(
            ToolCall(id = "1", name = "set_timer", argumentsJson = """{"hours":0,"minutes":1,"label":"tea"}"""),
        )

        if (!exactAlarmsAllowed) {
            assertTrue(result.isError)
            return@runBlocking
        }
        assertFalse("expected success, got: ${result.resultJson}", result.isError)
        val payload = json.parseToJsonElement(result.resultJson).jsonObject
        assertEquals("set", payload["status"]?.jsonPrimitive?.content)
        val triggersAt = payload["triggersAt"]?.jsonPrimitive?.long ?: error("missing triggersAt")
        // 1-minute timer: should land roughly 60s out, allow slack for test execution time.
        assertTrue(triggersAt in (before + 55_000)..(before + 65_000))
    }

    @Test
    fun setAlarmSchedulesTheNextOccurrenceOfThatTimeOfDay() = runBlocking {
        val result = provider.invoke(
            ToolCall(id = "2", name = "set_alarm", argumentsJson = """{"hours":9,"minutes":30}"""),
        )

        if (!exactAlarmsAllowed) {
            assertTrue(result.isError)
            return@runBlocking
        }
        assertFalse("expected success, got: ${result.resultJson}", result.isError)
        val payload = json.parseToJsonElement(result.resultJson).jsonObject
        val triggersAt = payload["triggersAt"]?.jsonPrimitive?.long ?: error("missing triggersAt")
        assertTrue("scheduled time should be in the future", triggersAt > System.currentTimeMillis())
        // Should never be more than 24h out — either later today or the same time tomorrow.
        assertTrue(triggersAt <= System.currentTimeMillis() + 24 * 60 * 60_000L)
    }

    @Test
    fun invalidArgumentsReturnAnErrorInsteadOfThrowing() = runBlocking {
        val result = provider.invoke(
            ToolCall(id = "3", name = "set_timer", argumentsJson = """{"hours":99,"minutes":0}"""),
        )
        assertTrue(result.isError)
    }

    @Test
    fun malformedJsonReturnsAnErrorInsteadOfThrowing() = runBlocking {
        val result = provider.invoke(
            ToolCall(id = "4", name = "set_timer", argumentsJson = "not json"),
        )
        assertTrue(result.isError)
    }

    @Test
    fun specsExposeBothTools() {
        val names = provider.specs.map { it.name }
        assertTrue(names.contains("set_alarm"))
        assertTrue(names.contains("set_timer"))
    }
}
