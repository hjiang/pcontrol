package com.pcontrol.app

/**
 * Tracks consecutive BLOCK_WEB actions for the same (subject, day)
 * to implement the 2-strikes fallback per §6.
 *
 * After 2 back-presses fail to navigate away from the blocked domain,
 * the third strike launches [BlockedActivity] instead.
 *
 * Usage:
 * - Call [recordStrike] each tick a BLOCK_WEB is enforced.
 * - Call [reset] when the domain changes or the verdict is no longer BLOCK_WEB.
 * - Call [shouldFallback] to decide whether to launch BlockedActivity.
 */
class WebBlockStrikes {

    private data class Key(val subject: String, val day: String)

    private val strikes = mutableMapOf<Key, Int>()

    /** Returns true after 2 back-presses (strike ≥ 3) — time to fall back. */
    fun shouldFallback(subject: String, day: String): Boolean {
        val key = Key(subject, day)
        val count = strikes.getOrDefault(key, 0)
        return count >= 3
    }

    /** Record a BLOCK_WEB enforcement for the given subject+day. */
    fun recordStrike(subject: String, day: String) {
        val key = Key(subject, day)
        strikes[key] = strikes.getOrDefault(key, 0) + 1
    }

    /** Reset strikes (domain changed, or verdict no longer BLOCK_WEB). */
    fun reset(subject: String) {
        strikes.keys.removeAll { it.subject == subject }
    }

    /** Clear all state. */
    fun resetAll() {
        strikes.clear()
    }
}
