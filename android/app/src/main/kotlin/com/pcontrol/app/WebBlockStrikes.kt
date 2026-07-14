package com.pcontrol.app

/**
 * Tracks consecutive BLOCK_WEB actions for the same (subject, day)
 * to implement the 2-strikes fallback per §6.
 *
 * After 2 back-presses fail to navigate away from the blocked domain,
 * the third strike requests the accessibility-owned blocking surface instead.
 *
 * Usage:
 * - Call [recordAndShouldFallback] only after a BACK action is dispatched.
 * - Call [reset] when the domain changes or the verdict is no longer BLOCK_WEB.
 * - Call [willFallbackOnNextStrike] to decide whether the next BACK would
 *   reach the blocking-surface fallback.
 */
class WebBlockStrikes {

    private data class Key(val subject: String, val day: String)

    private val strikes = mutableMapOf<Key, Int>()

    /** Returns true after 2 back-presses (strike ≥ 3) — time to fall back. */
    @Synchronized
    fun shouldFallback(subject: String, day: String): Boolean {
        val key = Key(subject, day)
        val count = strikes.getOrDefault(key, 0)
        return count >= 3
    }

    /** Record one strike and atomically report whether it reaches fallback. */
    @Synchronized
    fun recordAndShouldFallback(subject: String, day: String): Boolean {
        val key = Key(subject, day)
        val count = strikes.getOrDefault(key, 0) + 1
        strikes[key] = count
        return count >= 3
    }

    /** Returns true if recording the next dispatched BACK reaches fallback. */
    @Synchronized
    fun willFallbackOnNextStrike(subject: String, day: String): Boolean {
        val key = Key(subject, day)
        return strikes.getOrDefault(key, 0) + 1 >= 3
    }

    /** Record one dispatched BLOCK_WEB BACK action for the given subject+day. */
    @Synchronized
    fun recordStrike(subject: String, day: String) {
        val key = Key(subject, day)
        strikes[key] = strikes.getOrDefault(key, 0) + 1
    }

    /** Reset strikes (domain changed, or verdict no longer BLOCK_WEB). */
    @Synchronized
    fun reset(subject: String) {
        strikes.keys.removeAll { it.subject == subject }
    }

    /** Clear all state. */
    @Synchronized
    fun resetAll() {
        strikes.clear()
    }
}
