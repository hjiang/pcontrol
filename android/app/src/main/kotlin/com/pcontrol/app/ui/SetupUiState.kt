package com.pcontrol.app.ui

/**
 * Stage 2 — pure presentation state for the setup screen.
 *
 * This model lives in `:app` (it describes Android capabilities) but contains
 * **no Android APIs**, so it is fully unit-testable without Robolectric.
 *
 * Contract (Section 4.1 of plan 09):
 *  - Exactly one [SetupCapability] entry exists for each known [CapabilityId].
 *  - [SetupUiState.requiredComplete] counts only granted *required* entries.
 *  - [SetupUiState.canStart] is true **iff** every required entry is granted.
 *  - The optional updater capability never affects [canStart].
 *  - Capability order and section membership are stable so visual order and
 *    TalkBack traversal order agree.
 */
enum class CapabilityId {
    USAGE,
    ACCESSIBILITY,
    NOTIFICATIONS,
    BATTERY,
    SERVER,
    UPDATER,
}

/**
 * Raw, observable system facts collected by thin Android adapters.
 *
 * Constructed by the production permission provider from real Settings checks
 * and [com.pcontrol.app.SecretPrefs]; tests pass explicit values.
 *
 * Every field defaults to a safe "not granted" value so partial inputs degrade
 * predictably — but production always supplies every field.
 */
data class CapabilityFacts(
    val usage: Boolean = false,
    val accessibility: Boolean = false,
    val notifications: Boolean = false,
    val battery: Boolean = false,
    val server: Boolean = false,
    val updater: Boolean = false,
)

data class SetupCapability(
    val id: CapabilityId,
    val granted: Boolean,
    val required: Boolean,
)

data class SetupUiState(
    val capabilities: List<SetupCapability>,
    val requiredComplete: Int,
    val requiredTotal: Int,
    val canStart: Boolean,
) {
    /**
     * The first required capability that is not yet granted, in display order.
     * `null` when every required step is complete. Used to surface the "next
     * step" hint under the bottom CTA without re-scanning all capabilities.
     */
    val firstIncompleteRequired: SetupCapability?
        get() = capabilities.firstOrNull { it.required && !it.granted }

    companion object {
        /**
         * The stable display/section order. Every [CapabilityId] appears exactly
         * once; the order matches the on-screen card order so TalkBack
         * traversal and visual order agree (Section 4.1 invariant).
         */
        private val ORDER: List<Pair<CapabilityId, Boolean>> = listOf(
            CapabilityId.USAGE to true,
            CapabilityId.ACCESSIBILITY to true,
            CapabilityId.NOTIFICATIONS to true,
            CapabilityId.BATTERY to true,
            CapabilityId.SERVER to true,
            CapabilityId.UPDATER to false, // optional
        )

        /**
         * Map function name → granted state. Resolved once for each id so the
         * builder does not re-query permissions through `allPermissionsGranted()`.
         */
        private fun CapabilityFacts.grantedFor(id: CapabilityId): Boolean = when (id) {
            CapabilityId.USAGE -> usage
            CapabilityId.ACCESSIBILITY -> accessibility
            CapabilityId.NOTIFICATIONS -> notifications
            CapabilityId.BATTERY -> battery
            CapabilityId.SERVER -> server
            CapabilityId.UPDATER -> updater
        }

        /**
         * Build a [SetupUiState] from collected system facts.
         *
         * @throws IllegalStateException if [facts] somehow yields a state with
         *   duplicate or missing capability ids. With the enum-based design
         *   this cannot actually occur, but the contract fail-fast guard is
         *   declared explicitly (Section 4.1 postconditions).
         */
        fun build(facts: CapabilityFacts): SetupUiState {
            // Precondition: ORDER covers every CapabilityId exactly once.
            check(ORDER.size == CapabilityId.entries.size) {
                "ORDER must list every CapabilityId exactly once"
            }
            check(ORDER.map { it.first }.distinct().size == CapabilityId.entries.size) {
                "Duplicate capability ids in ORDER"
            }

            val capabilities: List<SetupCapability> = ORDER.map { (id, required) ->
                SetupCapability(
                    id = id,
                    granted = facts.grantedFor(id),
                    required = required,
                )
            }

            // Postcondition: exactly one entry per known CapabilityId.
            check(capabilities.size == CapabilityId.entries.size) {
                "Built capabilities must contain one entry per CapabilityId"
            }
            val ids = capabilities.map { it.id }
            check(ids.distinct().size == ids.size) {
                "Built capabilities contain duplicate ids"
            }

            val requiredCapabilities = capabilities.filter { it.required }
            val requiredTotal = requiredCapabilities.size
            val requiredComplete = requiredCapabilities.count { it.granted }
            val canStart = requiredCapabilities.all { it.granted }

            return SetupUiState(
                capabilities = capabilities,
                requiredComplete = requiredComplete,
                requiredTotal = requiredTotal,
                canStart = canStart,
            )
        }
    }
}