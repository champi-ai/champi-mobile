package ai.champi.assistant

import ai.champi.core.actions.ActionSettingsRepository
import ai.champi.core.notification.ProactiveNotificationPoster
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ProactiveNotificationEngine].
 *
 * Acceptance criteria (issue #39):
 * - Token-bucket capacity: with limit=3, the 4th [raise] within one hour is suppressed.
 * - Token replenishment: after advancing the clock past one hour, a new token becomes available.
 * - [raise] sets [CharacterState.NOTIFYING] and the notification is posted.
 * - Character reverts to [CharacterState.IDLE] within 3 s when the panel stays closed.
 * - Character does NOT revert to IDLE when the user opens the panel within the 3 s window.
 * - [urgent] flag is forwarded to [ProactiveNotificationPoster] unchanged.
 *
 * Sound/DND verification requires a physical device — noted as unverifiable in the PR.
 */
@RunWith(AndroidJUnit4::class)
class ProactiveNotificationEngineTest {

    private lateinit var appStateHolder: AppStateHolder
    private lateinit var actionSettingsRepository: ActionSettingsRepository
    private lateinit var fakePoster: FakeNotificationPoster
    private var fakeNow = 0L

    /** Fake clock whose time is advanced manually per test. */
    private val fakeClock = NotificationClock { fakeNow }

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        appStateHolder = AppStateHolder()
        actionSettingsRepository = ActionSettingsRepository(context)
        fakePoster = FakeNotificationPoster()
        fakeNow = System.currentTimeMillis()
        // Set a known rate limit.
        actionSettingsRepository.setProactiveRateLimitPerHour(3)
    }

    private fun buildEngine() = ProactiveNotificationEngine(
        poster = fakePoster,
        appStateHolder = appStateHolder,
        actionSettingsRepository = actionSettingsRepository,
        clock = fakeClock,
    )

    // --- Token-bucket rate limiter ---

    @Test
    fun raisePosts_withinCapacity() = runBlocking {
        val engine = buildEngine()
        engine.raise("hello", urgent = false)
        engine.raise("world", urgent = false)
        engine.raise("third", urgent = false)
        assertEquals("All three notifications within limit should be posted", 3, fakePoster.postCount)
    }

    @Test
    fun raise_suppressedWhenBucketEmpty() = runBlocking {
        val engine = buildEngine()
        repeat(3) { engine.raise("msg $it", urgent = false) }
        engine.raise("suppressed", urgent = false)
        assertEquals("4th notification should be suppressed", 3, fakePoster.postCount)
    }

    @Test
    fun raise_replenishesAfterOneHour() = runBlocking {
        val engine = buildEngine()
        repeat(3) { engine.raise("msg $it", urgent = false) }
        // Advance clock past one hour so the first token is replenished.
        fakeNow += 3_600_001L
        engine.raise("after-replenish", urgent = false)
        assertEquals("Token should replenish after one hour", 4, fakePoster.postCount)
    }

    @Test
    fun raise_partialReplenishment() = runBlocking {
        val engine = buildEngine()
        // Post 2 notifications, then advance half an hour.
        engine.raise("first", urgent = false)
        engine.raise("second", urgent = false)
        fakeNow += 1_800_000L // 30 min — neither token replenished yet
        // Bucket still has 1 token (capacity 3, 2 used in last 30 min).
        engine.raise("third", urgent = false)
        engine.raise("should-suppress", urgent = false)
        assertEquals("Third should post; fourth suppressed", 3, fakePoster.postCount)
    }

    // --- Character state ---

    @Test
    fun raise_setsNotifyingState() = runBlocking {
        val engine = buildEngine()
        engine.raise("ping", urgent = false)
        assertEquals(CharacterState.NOTIFYING, appStateHolder.state.value.characterState)
    }

    @Test
    fun raise_revertsToIdleAfter3sWhenPanelStaysClosed() = runBlocking {
        val engine = buildEngine()
        engine.raise("ping", urgent = false)
        withTimeout(5_000L) {
            appStateHolder.state.first { it.characterState == CharacterState.IDLE }
        }
        assertEquals(CharacterState.IDLE, appStateHolder.state.value.characterState)
    }

    @Test
    fun raise_doesNotRevertToIdleWhenPanelOpens() = runBlocking {
        val engine = buildEngine()
        engine.raise("ping", urgent = false)
        // Open the panel immediately — should cancel the revert timer.
        appStateHolder.requestOpenPanel()
        // Wait long enough for the 3 s window to expire if the revert hadn't been cancelled.
        delay(4_000L)
        assertFalse(
            "Character should not have reverted to IDLE after panel was opened",
            appStateHolder.state.value.characterState == CharacterState.IDLE,
        )
    }

    // --- Urgency flag ---

    @Test
    fun raise_urgentTrueForwardedToPoster() = runBlocking {
        val engine = buildEngine()
        engine.raise("urgent message", urgent = true)
        assertTrue("urgent flag should be forwarded to the poster", fakePoster.lastUrgent == true)
    }

    @Test
    fun raise_urgentFalseForwardedToPoster() = runBlocking {
        val engine = buildEngine()
        engine.raise("silent message", urgent = false)
        assertFalse("urgent=false should be forwarded to the poster", fakePoster.lastUrgent == true)
    }
}

/** Records calls to [post] for assertion in tests. */
private class FakeNotificationPoster : ProactiveNotificationPoster {
    var postCount = 0
    var lastUrgent: Boolean? = null

    override fun post(notificationId: Int, title: String, text: String, urgent: Boolean) {
        postCount++
        lastUrgent = urgent
    }
}
