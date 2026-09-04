package ai.champi.assistant

import ai.champi.core.persistence.QueuedTurnDao
import ai.champi.core.persistence.QueuedTurnEntity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replays turns that were queued during a provider-unavailability window (§3.3 step 4).
 *
 * Two triggers drive replay:
 * - A [ConnectivityManager.NetworkCallback] fires immediately when the network is restored.
 * - A periodic poll every [POLL_INTERVAL_MS] checks [RoutingPolicy.selectLlm] availability
 *   directly, so that an on-device model becoming ready (which isn't a connectivity event) also
 *   unblocks the queue.
 *
 * Replay is FIFO: [QueuedTurnDao.getOldest] is called in a loop; each turn is deleted after
 * handling (whether replayed or stale-noted). If a replay itself throws (provider went away mid-
 * drain), the retry count is incremented and the drain stops — the next trigger will retry.
 *
 * Stale-context threshold: if the conversation has gained more than [STALE_CONTEXT_THRESHOLD]
 * messages since the turn was enqueued, a system note is appended instead of re-submitting the
 * original input through [TurnOrchestrator]. This avoids replying to a question that has been
 * answered by subsequent turns or whose context is no longer coherent.
 *
 * Lifecycle: call [start] in [android.app.Service.onCreate] / [android.app.Service.onStartCommand]
 * with the service's [CoroutineScope]; call [stop] in [android.app.Service.onDestroy]. Both the
 * network callback and the polling coroutine are scoped to that lifetime.
 */
@Singleton
class QueueReplayWorker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queuedTurnDao: QueuedTurnDao,
    private val routingPolicy: RoutingPolicy,
    private val turnOrchestrator: TurnOrchestrator,
    private val conversationManager: ConversationManager,
) {
    private var pollJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Starts the periodic poll and registers the connectivity callback. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (pollJob?.isActive == true) return

        pollJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                drainIfAvailable()
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { drainIfAvailable() }
            }
        }
        networkCallback = callback

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    /** Cancels the poll loop and unregisters the connectivity callback. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null

        val callback = networkCallback ?: return
        networkCallback = null
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // callback was not registered (e.g. start() was never called successfully)
        }
    }

    /**
     * Drains the queue if an LLM provider is currently available. Called on each poll tick and on
     * each connectivity-restored event. Stops draining on the first replay failure, leaving the
     * remaining rows for the next trigger.
     */
    private suspend fun drainIfAvailable() {
        val hasProvider = try {
            routingPolicy.hasAvailableLlm()
        } catch (_: Exception) {
            false
        }
        if (!hasProvider) return

        while (true) {
            val turn = queuedTurnDao.getOldest() ?: break
            val handled = handleTurn(turn)
            if (!handled) break
            queuedTurnDao.delete(turn)
        }

        // A successful drain ends the outage window, so the next real failure can flash ERROR again.
        turnOrchestrator.resetErrorWindow()
    }

    /**
     * Processes one queued turn. Returns `true` if the turn was handled (either replayed or
     * replaced with a stale-context note) and should be deleted; `false` if the attempt failed
     * and the drain should stop.
     */
    private suspend fun handleTurn(turn: QueuedTurnEntity): Boolean {
        val currentCount = conversationManager.getMessageCount(turn.conversationId)
        val added = currentCount - turn.messageCountAtEnqueue

        return if (added > STALE_CONTEXT_THRESHOLD) {
            conversationManager.appendSystemMessage(STALE_CONTEXT_NOTE)
            true
        } else {
            try {
                turnOrchestrator.submitText(turn.inputText)
                true
            } catch (_: Exception) {
                queuedTurnDao.update(turn.copy(retryCount = turn.retryCount + 1))
                false
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L
        const val STALE_CONTEXT_THRESHOLD = 10
        const val STALE_CONTEXT_NOTE =
            "champi couldn't respond earlier due to provider unavailability"
    }
}
