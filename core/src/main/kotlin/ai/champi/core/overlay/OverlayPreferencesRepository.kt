package ai.champi.core.overlay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Plain (non-Compose) pixel offset, so `:core` doesn't need a dependency on Compose UI. */
data class BubbleOffset(val x: Int, val y: Int)

private val Context.overlayDataStore: DataStore<Preferences> by preferencesDataStore(name = "overlay_prefs")

/** Persists bubble position and the quick-actions geometry choice across process restarts. */
@Singleton
class OverlayPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore get() = context.overlayDataStore

    val bubbleOffset: Flow<BubbleOffset> = dataStore.data.map { prefs ->
        BubbleOffset(
            x = prefs[KEY_BUBBLE_X] ?: 0,
            y = prefs[KEY_BUBBLE_Y] ?: DEFAULT_BUBBLE_Y,
        )
    }

    suspend fun saveBubbleOffset(offset: BubbleOffset) {
        dataStore.edit { prefs ->
            prefs[KEY_BUBBLE_X] = offset.x
            prefs[KEY_BUBBLE_Y] = offset.y
        }
    }

    val quickActionGeometry: Flow<QuickActionGeometry> = dataStore.data.map { prefs ->
        prefs[KEY_QUICK_ACTION_GEOMETRY]
            ?.let { name -> runCatching { QuickActionGeometry.valueOf(name) }.getOrNull() }
            ?: QuickActionGeometry.RADIAL_ARC
    }

    suspend fun setQuickActionGeometry(geometry: QuickActionGeometry) {
        dataStore.edit { it[KEY_QUICK_ACTION_GEOMETRY] = geometry.name }
    }

    private companion object {
        val KEY_BUBBLE_X = intPreferencesKey("bubble_x")
        val KEY_BUBBLE_Y = intPreferencesKey("bubble_y")
        val KEY_QUICK_ACTION_GEOMETRY = stringPreferencesKey("quick_action_geometry")
        const val DEFAULT_BUBBLE_Y = 600
    }
}
