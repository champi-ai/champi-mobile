package ai.champi.core.actions

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionSettingsRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ActionSettingsRepository(context)

    @Test
    fun defaultsAreActionsEnabledAndThreePerHour() = runBlocking {
        assertTrue(repository.alarmActionsEnabled.first())
        assertTrue(repository.calendarActionsEnabled.first())
        assertEquals(3, repository.proactiveRateLimitPerHour.first())
    }

    @Test
    fun alarmActionsEnabledRoundTrips() = runBlocking {
        repository.setAlarmActionsEnabled(false)
        assertEquals(false, repository.alarmActionsEnabled.first())
        repository.setAlarmActionsEnabled(true)
        assertEquals(true, repository.alarmActionsEnabled.first())
    }

    @Test
    fun calendarActionsEnabledRoundTrips() = runBlocking {
        repository.setCalendarActionsEnabled(false)
        assertEquals(false, repository.calendarActionsEnabled.first())
        repository.setCalendarActionsEnabled(true)
        assertEquals(true, repository.calendarActionsEnabled.first())
    }

    @Test
    fun proactiveRateLimitRoundTrips() = runBlocking {
        repository.setProactiveRateLimitPerHour(7)
        assertEquals(7, repository.proactiveRateLimitPerHour.first())
        repository.setProactiveRateLimitPerHour(3)
    }
}
