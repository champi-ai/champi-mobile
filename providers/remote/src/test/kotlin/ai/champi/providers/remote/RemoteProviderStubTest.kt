package ai.champi.providers.remote

import ai.champi.providers.api.Locality
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the remote STT and TTS stubs.
 *
 * These tests run on the JVM without an Android context or Hilt graph — the stubs have no
 * constructor dependencies, so plain instantiation is sufficient to verify the contract.
 */
class RemoteProviderStubTest {

    // ---------------------------------------------------------------------------
    // RemoteSttProvider
    // ---------------------------------------------------------------------------

    @Test
    fun remoteSttProvider_availableReturnsFalse() = runTest {
        val provider = RemoteSttProvider()
        assertFalse(provider.available())
    }

    @Test
    fun remoteSttProvider_localityIsRemote() {
        val provider = RemoteSttProvider()
        assertEquals(Locality.REMOTE, provider.locality)
    }

    @Test
    fun remoteSttProvider_capabilitiesAreNonPlaceholder() {
        val provider = RemoteSttProvider()
        assertTrue(provider.capabilities.languages.isNotEmpty())
        assertTrue(provider.capabilities.maxInputTokens > 0)
    }

    @Test
    fun remoteSttProvider_transcribeReturnsEmptyFlow() = runTest {
        val provider = RemoteSttProvider()
        val results = provider.transcribe(kotlinx.coroutines.flow.emptyFlow()).toList()
        assertTrue(results.isEmpty())
    }

    // ---------------------------------------------------------------------------
    // RemoteTtsProvider
    // ---------------------------------------------------------------------------

    @Test
    fun remoteTtsProvider_availableReturnsFalse() = runTest {
        val provider = RemoteTtsProvider()
        assertFalse(provider.available())
    }

    @Test
    fun remoteTtsProvider_localityIsRemote() {
        val provider = RemoteTtsProvider()
        assertEquals(Locality.REMOTE, provider.locality)
    }

    @Test
    fun remoteTtsProvider_capabilitiesAreNonPlaceholder() {
        val provider = RemoteTtsProvider()
        assertTrue(provider.capabilities.languages.isNotEmpty())
        assertTrue(provider.capabilities.maxInputTokens > 0)
    }

    @Test
    fun remoteTtsProvider_synthesizeReturnsEmptyFlow() = runTest {
        val provider = RemoteTtsProvider()
        val chunks = provider.synthesize(kotlinx.coroutines.flow.emptyFlow()).toList()
        assertTrue(chunks.isEmpty())
    }
}
