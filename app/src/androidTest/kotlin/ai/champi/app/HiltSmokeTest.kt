package ai.champi.app

import ai.champi.core.Logger
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Verifies that the Hilt DI graph wires [Logger] from :core into :app correctly.
 * The test passes if the graph compiles and [Logger] is injected without errors.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HiltSmokeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var logger: Logger

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun logger_isInjected() {
        assertNotNull(logger)
    }
}
