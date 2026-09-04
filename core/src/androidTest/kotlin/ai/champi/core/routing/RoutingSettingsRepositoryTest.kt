package ai.champi.core.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance criterion for issue #32: routing settings round-trip through DataStore. */
@RunWith(AndroidJUnit4::class)
class RoutingSettingsRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = RoutingSettingsRepository(context)

    @Test
    fun edgeOnlyModeDefaultsToFalse() = runBlocking {
        assertFalse(repository.edgeOnlyMode.first())
    }

    @Test
    fun edgeOnlyModeRoundTrips() = runBlocking {
        repository.setEdgeOnlyMode(true)
        assertTrue(repository.edgeOnlyMode.first())
        repository.setEdgeOnlyMode(false)
        assertFalse(repository.edgeOnlyMode.first())
    }

    @Test
    fun providerEnablesDefaultToTrue() = runBlocking {
        assertTrue(repository.edgeSttEnabled.first())
        assertTrue(repository.edgeLlmEnabled.first())
        assertTrue(repository.edgeTtsEnabled.first())
        assertTrue(repository.remoteSttEnabled.first())
        assertTrue(repository.remoteLlmEnabled.first())
        assertTrue(repository.remoteTtsEnabled.first())
    }

    @Test
    fun edgeProviderEnablesRoundTrip() = runBlocking {
        repository.setEdgeSttEnabled(false)
        assertEquals(false, repository.edgeSttEnabled.first())

        repository.setEdgeLlmEnabled(false)
        assertEquals(false, repository.edgeLlmEnabled.first())

        repository.setEdgeTtsEnabled(false)
        assertEquals(false, repository.edgeTtsEnabled.first())

        repository.setEdgeSttEnabled(true)
        repository.setEdgeLlmEnabled(true)
        repository.setEdgeTtsEnabled(true)
    }

    @Test
    fun remoteProviderEnablesRoundTrip() = runBlocking {
        repository.setRemoteSttEnabled(false)
        assertEquals(false, repository.remoteSttEnabled.first())

        repository.setRemoteLlmEnabled(false)
        assertEquals(false, repository.remoteLlmEnabled.first())

        repository.setRemoteTtsEnabled(false)
        assertEquals(false, repository.remoteTtsEnabled.first())

        repository.setRemoteSttEnabled(true)
        repository.setRemoteLlmEnabled(true)
        repository.setRemoteTtsEnabled(true)
    }
}
