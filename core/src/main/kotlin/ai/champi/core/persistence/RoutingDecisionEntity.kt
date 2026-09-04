package ai.champi.core.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Which pipeline stage a routing decision was made for. */
enum class RoutingStage { STT, LLM, TTS }

/**
 * Why the routing policy selected (or failed to select) a provider.
 *
 * - [EDGE_ONLY]: `edgeOnly` flag was set; edge returned unconditionally.
 * - [EDGE_FIT]: edge was available and the request fit within its capability budget.
 * - [REMOTE_FALLBACK]: edge was unavailable or the request did not fit; remote was used.
 * - [DEGRADE]: no provider was available; [ai.champi.assistant.NoProviderException] was thrown.
 */
enum class RoutingReason { EDGE_ONLY, EDGE_FIT, REMOTE_FALLBACK, DEGRADE }

/**
 * Records one provider-selection event for offline heuristic tuning and user-visible routing
 * transparency (§3.3). Inspectable via `adb shell sqlite3 /data/data/…/databases/champi.db
 * "SELECT * FROM routing_decisions ORDER BY timestamp DESC LIMIT 20;"`.
 */
@Entity(tableName = "routing_decisions")
data class RoutingDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch milliseconds at which the selection was made. */
    val timestamp: Long,
    val stage: RoutingStage,
    /**
     * [ai.champi.providers.api.Provider.id] of the selected provider, or an empty string when
     * [reason] is [RoutingReason.DEGRADE] and no provider could be selected.
     */
    val selectedProviderId: String,
    /**
     * Locality of the selected provider ("EDGE" / "REMOTE"), or an empty string for
     * [RoutingReason.DEGRADE].
     */
    val locality: String,
    val reason: RoutingReason,
    /** Rough token estimate for the input that triggered this decision (input chars ÷ 4). */
    val inputTokenEstimate: Int,
)
