package ai.champi.core.routing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.routingSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "routing_settings")

/** Persists routing policy toggles: the edge-only flag and per-locality provider enable switches. */
@Singleton
class RoutingSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.routingSettingsDataStore

    /** When `true`, remote providers are never selected regardless of edge availability. */
    val edgeOnlyMode: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_EDGE_ONLY_MODE] ?: false }

    suspend fun setEdgeOnlyMode(enabled: Boolean) {
        dataStore.edit { it[KEY_EDGE_ONLY_MODE] = enabled }
    }

    val edgeSttEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_EDGE_STT_ENABLED] ?: true }

    suspend fun setEdgeSttEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EDGE_STT_ENABLED] = enabled }
    }

    val edgeLlmEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_EDGE_LLM_ENABLED] ?: true }

    suspend fun setEdgeLlmEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EDGE_LLM_ENABLED] = enabled }
    }

    val edgeTtsEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_EDGE_TTS_ENABLED] ?: true }

    suspend fun setEdgeTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EDGE_TTS_ENABLED] = enabled }
    }

    val remoteSttEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_REMOTE_STT_ENABLED] ?: true }

    suspend fun setRemoteSttEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_REMOTE_STT_ENABLED] = enabled }
    }

    val remoteLlmEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_REMOTE_LLM_ENABLED] ?: true }

    suspend fun setRemoteLlmEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_REMOTE_LLM_ENABLED] = enabled }
    }

    val remoteTtsEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_REMOTE_TTS_ENABLED] ?: true }

    suspend fun setRemoteTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_REMOTE_TTS_ENABLED] = enabled }
    }

    private companion object {
        val KEY_EDGE_ONLY_MODE = booleanPreferencesKey("edge_only_mode")
        val KEY_EDGE_STT_ENABLED = booleanPreferencesKey("edge_stt_enabled")
        val KEY_EDGE_LLM_ENABLED = booleanPreferencesKey("edge_llm_enabled")
        val KEY_EDGE_TTS_ENABLED = booleanPreferencesKey("edge_tts_enabled")
        val KEY_REMOTE_STT_ENABLED = booleanPreferencesKey("remote_stt_enabled")
        val KEY_REMOTE_LLM_ENABLED = booleanPreferencesKey("remote_llm_enabled")
        val KEY_REMOTE_TTS_ENABLED = booleanPreferencesKey("remote_tts_enabled")
    }
}
