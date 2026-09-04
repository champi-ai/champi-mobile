package ai.champi.assistant

/**
 * Thrown by [RoutingPolicy] when no provider is available for a requested stage — i.e. both edge
 * and remote candidates are absent, disabled, or report `available() == false`. Callers must not
 * swallow this exception; it surfaces the routing degrade path (§3.3 step 4).
 */
class NoProviderException(message: String) : Exception(message)
