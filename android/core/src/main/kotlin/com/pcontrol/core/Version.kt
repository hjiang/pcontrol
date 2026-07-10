package com.pcontrol.core

/**
 * Semver parsing and comparison utilities for Android auto-update.
 *
 * Normalizes tag prefixes (`android-v`, `android-`, `v`) before parsing
 * strict `X.Y.Z` semver. Pre-release suffixes (e.g. `-rc1`) are not
 * handled — only pure `X.Y.Z` strings are accepted; this is a deliberate
 * simplification because all production releases use plain semver tags.
 *
 * Design note: this lives in `:core` (pure JVM) so it's testable with no
 * Android dependencies. See AGENTS.md § "keep :core pure".
 */
object Version {

    /**
     * Normalizes a version tag to raw `X.Y.Z` semver.
     *
     * Strips optional `android-` prefix, then optional `v` prefix, then
     * validates the result is a strict `X.Y.Z` triplet of non-negative
     * integers. Returns `null` on invalid input (empty, non-numeric, wrong
     * segment count).
     */
    fun parse(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val s = trimmed
            .removePrefix("android-")
            .removePrefix("v")

        val parts = s.split('.')
        if (parts.size != 3) return null
        // Each part must be a non-negative integer that fits in an Int
        for (part in parts) {
            if (part.isEmpty()) return null
            if (!part.all { it.isDigit() }) return null
            // Reject segments that overflow Int — they'd cause confusing failures later
            val longVal = part.toLongOrNull() ?: return null
            if (longVal > Int.MAX_VALUE) return null
        }
        return s
    }

    /**
     * Compares two version strings after normalization.
     *
     * @return a negative integer if a < b, 0 if equal, positive if a > b,
     *         or `null` if either version cannot be parsed.
     */
    fun compare(a: String, b: String): Int? {
        val na = parse(a) ?: return null
        val nb = parse(b) ?: return null
        val partsA = na.split('.').map { it.toIntOrNull() ?: return null }
        val partsB = nb.split('.').map { it.toIntOrNull() ?: return null }
        for (i in 0..2) {
            val cmp = partsA[i].compareTo(partsB[i])
            if (cmp != 0) return cmp
        }
        return 0
    }

    /**
     * Determines whether an update is available.
     *
     * @param installedVersion the currently installed version (e.g. `"1.0.0"`).
     * @param releaseVersion the version from the latest release tag (e.g. `"android-v1.2.3"`).
     * @return [UpdateCheckResult] or `null` if either version cannot be parsed.
     */
    fun needsUpdate(installedVersion: String, releaseVersion: String): UpdateCheckResult? {
        val cmp = compare(releaseVersion, installedVersion) ?: return null
        return if (cmp > 0) UpdateCheckResult.NEEDS_UPDATE else UpdateCheckResult.UP_TO_DATE
    }
}

/**
 * Result of checking whether a newer version is available.
 */
enum class UpdateCheckResult {
    /** A newer version exists and should be downloaded. */
    NEEDS_UPDATE,
    /** The installed version is current or ahead. */
    UP_TO_DATE
}
