package ai.champi.core.actions

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.actionSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "action_settings")

/** Per-action enable toggles and the proactive-notification rate limit; shared by all `:actions`
 *  providers and the (future) proactive notification engine. */
@Singleton
class ActionSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.actionSettingsDataStore

    val alarmActionsEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_ALARM_ACTIONS_ENABLED] ?: true }

    suspend fun setAlarmActionsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ALARM_ACTIONS_ENABLED] = enabled }
    }

    val calendarActionsEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_CALENDAR_ACTIONS_ENABLED] ?: true }

    suspend fun setCalendarActionsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CALENDAR_ACTIONS_ENABLED] = enabled }
    }

    val proactiveRateLimitPerHour: Flow<Int> =
        dataStore.data.map { prefs -> prefs[KEY_PROACTIVE_RATE_LIMIT_PER_HOUR] ?: DEFAULT_RATE_LIMIT_PER_HOUR }

    suspend fun setProactiveRateLimitPerHour(limit: Int) {
        dataStore.edit { it[KEY_PROACTIVE_RATE_LIMIT_PER_HOUR] = limit }
    }

    private companion object {
        val KEY_ALARM_ACTIONS_ENABLED = booleanPreferencesKey("alarm_actions_enabled")
        val KEY_CALENDAR_ACTIONS_ENABLED = booleanPreferencesKey("calendar_actions_enabled")
        val KEY_PROACTIVE_RATE_LIMIT_PER_HOUR = intPreferencesKey("proactive_rate_limit_per_hour")
        const val DEFAULT_RATE_LIMIT_PER_HOUR = 3
    }
}
