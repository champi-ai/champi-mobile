package ai.champi.core.context

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

private val Context.contextSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "context_settings")

/**
 * Persists the four optional ambient-context signal toggles. All signals default to `false`
 * (opt-in), per the V1 privacy requirement that no sensor is read without explicit user consent.
 *
 * Follows the same DataStore-repository pattern as [ai.champi.core.routing.RoutingSettingsRepository]
 * and [ai.champi.core.actions.ActionSettingsRepository]: one dedicated DataStore file, `@Singleton`,
 * `Flow<Boolean>` reads, `suspend set*()` writes.
 */
@Singleton
class ContextSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.contextSettingsDataStore

    /** When `true`, coarse location is collected and injected as context.
     *  Requires [android.Manifest.permission.ACCESS_COARSE_LOCATION]. */
    val locationContextEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_LOCATION_CONTEXT_ENABLED] ?: false }

    suspend fun setLocationContextEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_LOCATION_CONTEXT_ENABLED] = enabled }
    }

    /** When `true`, battery level and charging status are collected and injected as context. */
    val batteryContextEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_BATTERY_CONTEXT_ENABLED] ?: false }

    suspend fun setBatteryContextEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BATTERY_CONTEXT_ENABLED] = enabled }
    }

    /** When `true`, the active network connectivity type is collected and injected as context. */
    val connectivityContextEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_CONNECTIVITY_CONTEXT_ENABLED] ?: false }

    suspend fun setConnectivityContextEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_CONNECTIVITY_CONTEXT_ENABLED] = enabled }
    }

    /**
     * When `true`, the foreground app's package name is collected and injected as context.
     *
     * This signal requires the special `PACKAGE_USAGE_STATS` AppOps permission, which is NOT a
     * normal runtime permission. The user must grant it manually via
     * [android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS]. Enabling this toggle does not
     * trigger an automatic permission dialog — the app directs the user to the system settings
     * screen instead, and gracefully skips this signal if the permission has not been granted.
     */
    val foregroundAppContextEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_FOREGROUND_APP_CONTEXT_ENABLED] ?: false }

    suspend fun setForegroundAppContextEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_FOREGROUND_APP_CONTEXT_ENABLED] = enabled }
    }

    private companion object {
        val KEY_LOCATION_CONTEXT_ENABLED = booleanPreferencesKey("location_context_enabled")
        val KEY_BATTERY_CONTEXT_ENABLED = booleanPreferencesKey("battery_context_enabled")
        val KEY_CONNECTIVITY_CONTEXT_ENABLED = booleanPreferencesKey("connectivity_context_enabled")
        val KEY_FOREGROUND_APP_CONTEXT_ENABLED = booleanPreferencesKey("foreground_app_context_enabled")
    }
}
