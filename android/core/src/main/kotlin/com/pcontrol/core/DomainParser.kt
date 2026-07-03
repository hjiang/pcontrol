package com.pcontrol.core

import java.net.URI

/**
 * Extracts the registrable domain from a URL string.
 *
 * Uses a small embedded public-suffix list matching §6 rules.
 * Returns null for unparseable input (mid-typing, search queries, empty).
 */
object DomainParser {

    // Common suffixes (the "public suffix" list, v1 subset).
    // A suffix like "co.uk" means "*.co.uk" where the registrable domain
    // is two labels above the suffix.
    private val publicSuffixes: Map<String, Int> = mapOf(
        // Standard TLDs (registrable domain = suffix + 1 label)
        "com" to 1,
        "org" to 1,
        "net" to 1,
        "io" to 1,
        "edu" to 1,
        "gov" to 1,
        "co" to 1,
        "me" to 1,
        "app" to 1,
        "dev" to 1,
        "tv" to 1,

        // Two-part suffixed TLDs (registrable domain = suffix + 1 label)
        "co.uk" to 1,
        "org.uk" to 1,
        "ac.uk" to 1,
        "gov.uk" to 1,
        "co.jp" to 1,
        "com.au" to 1,
        "co.nz" to 1,
        "co.kr" to 1,
    )

    /**
     * Extracts the registrable domain from a URL.
     *
     * Examples:
     * - "https://m.youtube.com/watch?v=xyz" → "youtube.com"
     * - "http://localhost/path" → null
     * - "just text" → null
     */
    fun parse(input: String): String? {
        if (input.isBlank()) return null

        val host = extractHost(input) ?: return null
        if (host.isEmpty()) return null

        // Check for IP address
        if (isIpAddress(host)) return host

        // Split into labels
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null // Need at least "example.com"

        // Try to match the longest suffix first
        val hostLower = host.lowercase()

        // Sort suffixes by length descending for longest match
        val matched = publicSuffixes.entries
            .filter { (suffix, _) -> hostLower.endsWith(".$suffix") || hostLower == suffix }
            .maxByOrNull { (suffix, _) -> suffix.length }

        if (matched != null) {
            val (suffix, extraLabels) = matched
            val suffixPartCount = suffix.count { it == '.' } + 1
            val neededLabels = suffixPartCount + extraLabels

            if (labels.size >= neededLabels) {
                return labels.takeLast(neededLabels).joinToString(".")
            }
            return labels.joinToString(".")
        }

        // Default: registrable domain is the last 2 labels
        return if (labels.size >= 2) {
            labels.takeLast(2).joinToString(".")
        } else {
            null
        }
    }

    private fun extractHost(input: String): String? {
        return try {
            // Add scheme if missing so URI can parse it
            val url = if (input.contains("://")) input else "http://$input"
            val uri = URI(url)
            var host = uri.host?.lowercase()?.trimStart('.')
            // Strip surrounding brackets from IPv6
            if (host != null && host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length - 1)
            }
            host
        } catch (e: Exception) {
            // For inputs like "[::1]" that Java URI can't parse directly
            val clean = input.trim().removeSurrounding("http://", "").removeSurrounding("https://", "")
            val firstSlash = clean.indexOf('/')
            val firstQ = clean.indexOf('?')
            val hostEnd = when {
                firstSlash > 0 && firstQ > 0 -> minOf(firstSlash, firstQ)
                firstSlash > 0 -> firstSlash
                firstQ > 0 -> firstQ
                else -> clean.length
            }
            val maybeHost = clean.substring(0, hostEnd)
                .removeSurrounding("[", "]")
            if (maybeHost.contains('.') || maybeHost.contains(':')) maybeHost.lowercase() else null
        }
    }

    private fun isIpAddress(host: String): Boolean {
        // IPv6 in brackets
        val h = host.removeSurrounding("[", "]")
        // Try IPv4 (dotted quad)
        if (h.count { it == '.' } == 3) {
            val parts = h.split('.')
            if (parts.size == 4 && parts.all { it.all { c -> c.isDigit() } }) {
                return true
            }
        }
        // Try IPv6 (contains colon)
        if (h.contains(':')) return true
        return false
    }
}
