package ai.champi.core.context

/**
 * Contract for anything that can produce a fresh [ContextSnapshot] on demand.
 *
 * Defined in `:core` so that `:assistant`'s `TurnOrchestrator` can depend on this interface
 * without creating a circular dependency on `:context`, which implements it.
 */
interface ContextSnapshotSource {
    /**
     * Reads whichever context signals are currently enabled and returns a fresh [ContextSnapshot].
     * The snapshot's fields are null for any signal that is disabled or for which the required
     * permission has not been granted. Must never throw; signal-collection failures are silently
     * skipped and the corresponding field is left null.
     */
    suspend fun readSnapshot(): ContextSnapshot
}
