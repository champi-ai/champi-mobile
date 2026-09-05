package ai.champi.providers.api

/**
 * Number of characters approximated as one token. Used uniformly across the routing heuristic
 * ([RoutingPolicy.fits][ai.champi.assistant.RoutingPolicy]) and the context-window builder
 * ([ContextWindowBuilder][ai.champi.assistant.ContextWindowBuilder]) so both see the same token
 * counts rather than diverging formulas.
 *
 * The approximation (4 chars ≈ 1 token) is well-established for English text and aligns with the
 * estimate already embedded in [Conversation.totalTokens]. It deliberately sacrifices per-model
 * accuracy (tokenisers vary) for zero-dependency, deterministic computation at runtime.
 */
const val CHARS_PER_TOKEN: Int = 4

/**
 * Estimates the token count of [text] using the [CHARS_PER_TOKEN] approximation.
 * Returns 0 for blank input.
 */
fun estimateTokens(text: String): Int = text.length / CHARS_PER_TOKEN
