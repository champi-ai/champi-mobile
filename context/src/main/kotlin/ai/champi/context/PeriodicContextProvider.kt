package ai.champi.context

import ai.champi.core.context.ContextSettingsRepository
import ai.champi.core.context.ContextSnapshot
import ai.champi.core.context.ContextSnapshotSource
import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Process
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** Default interval between periodic context snapshots in milliseconds (5 minutes). */
private const val DEFAULT_INTERVAL_MS = 5L * 60 * 1000

/**
 * Collects optional ambient context signals (location, battery, connectivity, foreground app)
 * on a configurable interval and exposes them as a [Flow]. Implements [ContextSnapshotSource]
 * for on-demand per-turn reads from `TurnOrchestrator`.
 *
 * All four signals are individually toggled via [ContextSettingsRepository]; they all default to
 * disabled. When every signal is disabled, [readSnapshot] returns an all-null [ContextSnapshot]
 * immediately — no permission-gated API is called.
 *
 * Permission handling:
 * - `ACCESS_COARSE_LOCATION`: standard runtime permission checked via [ContextCompat.checkSelfPermission]
 *   before every location read. If not granted, the location fields are left null even if the
 *   toggle is on (defense-in-depth).
 * - `PACKAGE_USAGE_STATS`: a special AppOps permission that is NOT a normal runtime permission.
 *   It is granted manually by the user via [android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS].
 *   Checked via [AppOpsManager.checkOpNoThrow] before every usage-stats read.
 */
@Singleton
class PeriodicContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: ContextSettingsRepository,
) : ContextSnapshotSource {

    /**
     * Emits a fresh [ContextSnapshot] at [intervalMs] intervals for as long as the flow is
     * collected. If all four toggles are disabled at a given tick, the snapshot is still emitted
     * (with all-null fields) so downstream collectors can react to the disabled state.
     *
     * The flow performs no I/O on the initial tick — it waits [intervalMs] before the first
     * collection, matching the "periodic" semantic.
     */
    fun contextFlow(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<ContextSnapshot> = flow {
        while (true) {
            delay(intervalMs)
            emit(readSnapshot())
        }
    }

    /**
     * Reads all enabled signals synchronously and returns the resulting [ContextSnapshot].
     * This is the path used by `TurnOrchestrator` for fresh per-turn context injection.
     *
     * Short-circuits immediately (returns an all-null snapshot) when all four toggles are
     * disabled, touching no permission-gated API.
     */
    override suspend fun readSnapshot(): ContextSnapshot {
        val locationEnabled = settings.locationContextEnabled.first()
        val batteryEnabled = settings.batteryContextEnabled.first()
        val connectivityEnabled = settings.connectivityContextEnabled.first()
        val foregroundAppEnabled = settings.foregroundAppContextEnabled.first()

        // No-op short-circuit: if every signal is disabled, return an empty snapshot without
        // calling any permission-gated API.
        if (!locationEnabled && !batteryEnabled && !connectivityEnabled && !foregroundAppEnabled) {
            return ContextSnapshot()
        }

        var latitude: Double? = null
        var longitude: Double? = null
        var batteryPercent: Int? = null
        var isCharging: Boolean? = null
        var connectivityType: String? = null
        var foregroundAppPackage: String? = null

        if (locationEnabled) {
            readLocation()?.let { (lat, lon) ->
                latitude = lat
                longitude = lon
            }
        }

        if (batteryEnabled) {
            readBattery()?.let { (pct, charging) ->
                batteryPercent = pct
                isCharging = charging
            }
        }

        if (connectivityEnabled) {
            connectivityType = readConnectivity()
        }

        if (foregroundAppEnabled) {
            foregroundAppPackage = readForegroundApp()
        }

        return ContextSnapshot(
            latitude = latitude,
            longitude = longitude,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            connectivityType = connectivityType,
            foregroundAppPackage = foregroundAppPackage,
        )
    }

    /**
     * Returns the device's last known coarse location as (latitude, longitude), or null if the
     * [Manifest.permission.ACCESS_COARSE_LOCATION] permission is not granted or no cached location
     * is available. Uses [LocationManager] — no Google Play Services dependency required.
     *
     * The runtime permission check immediately preceding the [LocationManager] call satisfies the
     * permission requirement; @SuppressLint tells lint that we are intentionally managing this.
     */
    @SuppressLint("MissingPermission")
    private fun readLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: return null
        return location.latitude to location.longitude
    }

    /**
     * Returns (batteryPercent, isCharging) by reading the sticky [Intent.ACTION_BATTERY_CHANGED]
     * broadcast. Never requires a runtime permission.
     */
    private fun readBattery(): Pair<Int, Boolean>? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val pct = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    /**
     * Returns a human-readable connectivity type string via [ConnectivityManager].
     *
     * [ConnectivityManager.getActiveNetwork] and [ConnectivityManager.getNetworkCapabilities]
     * require [android.Manifest.permission.ACCESS_NETWORK_STATE], which is a `normal`-protection-
     * level permission declared in the app manifest — it is auto-granted at install time with no
     * runtime dialog needed. @SuppressLint informs lint that the permission is covered by the
     * manifest declaration.
     */
    @SuppressLint("MissingPermission")
    private fun readConnectivity(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        return when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    }

    /**
     * Returns the package name of the most recently used app by querying [UsageStatsManager].
     *
     * IMPORTANT: `PACKAGE_USAGE_STATS` is NOT a normal runtime permission. It is a special
     * AppOps permission granted by the user via [android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS].
     * The app declares it in the manifest with `tools:ignore="ProtectedPermissions"` and checks
     * it here via [AppOpsManager.checkOpNoThrow] before touching any usage-stats API. If the
     * permission has not been granted, this method returns null without throwing.
     *
     * @SuppressLint covers the [UsageStatsManager.queryUsageStats] MissingPermission warning;
     * the AppOps check in [hasUsageStatsPermission] is the runtime guard.
     */
    @SuppressLint("MissingPermission")
    private fun readForegroundApp(): String? {
        if (!hasUsageStatsPermission()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        // Query the last 60 seconds to find the most recently used app.
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
            ?: return null
        return stats.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        // minSdk = 29 (Q), so unsafeCheckOpNoThrow is always available. It is deprecated at API 34
        // but no non-deprecated equivalent with the same signature exists across our full API range,
        // so we suppress the warning rather than branching on API 34+.
        @Suppress("DEPRECATION")
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
