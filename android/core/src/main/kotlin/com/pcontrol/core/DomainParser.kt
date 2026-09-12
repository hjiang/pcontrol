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
            // Reject title-like text: a real host cannot contain whitespace.
            // A colon is only legitimate here inside a syntactically valid IPv6
            // literal — a port is parsed by URI, never this fallback — so any
            // other colon-bearing or dot-bearing candidate is title-like
            // garbage and must be rejected.
            val hasWhitespace = maybeHost.any { it.isWhitespace() }
            if (hasWhitespace) {
                null
            } else if (maybeHost.contains(':')) {
                if (isValidIpv6(maybeHost)) maybeHost.lowercase() else null
            } else if (maybeHost.contains('.')) {
                maybeHost.lowercase()
            } else {
                null
            }
        }
    }

    private fun isIpAddress(host: String): Boolean {
        // IPv6 in brackets
        val h = host.removeSurrounding("[", "]")
        // Try IPv4 (dotted quad)
        if (h.count { it == '.' } == 3) {
            val parts = h.split('.')
            if (parts.size == 4 && parts.all { it.all { c -> c in '0'..'9' } }) {
                return true
            }
        }
        // Try IPv6: must be a syntactically valid literal, not merely contain
        // two colons (title-like text such as "cnn:breaking:news" would pass a
        // bare colon count and become a bogus web subject).
        return isValidIpv6(h)
    }

    /**
     * Minimal syntactic IPv6 validation (RFC 4291 §2.2): one to eight groups of
     * 1-4 hex digits separated by single colons, at most one "::" compression,
     * and an optional embedded IPv4 tail ("::ffff:192.0.2.128"). Callers pass a
     * bracket-normalized host, so a second bracket pair fails the hex-group
     * checks instead of being silently stripped. Only ASCII hex digits count —
     * Char.isDigit() alone would accept non-ASCII decimal digits. Title-like
     * text ("cnn:breaking:news", "CNN:Breaking.news", unterminated brackets)
     * never matches, while real literals like "::1", "fe80::1", "2001:db8::1"
     * and "::ffff:192.0.2.128" do.
     */
    private fun isValidIpv6(host: String): Boolean {
        if (host.isEmpty() || host.any { it.isWhitespace() }) return false

        // Optional embedded IPv4 tail: the last group may be a dotted quad (the
        // final 32 bits). Validate it strictly, then substitute two hex groups
        // so the remainder is checked uniformly.
        val body = if (host.contains('.')) {
            val tailStart = host.lastIndexOf(':') + 1
            val octets = host.substring(tailStart).split('.')
            if (octets.size != 4 ||
                octets.any { o ->
                    o.isEmpty() || o.length > 3 || o.any { c -> c !in '0'..'9' } || o.toInt() > 255
                }
            ) {
                return false
            }
            host.substring(0, tailStart) + "0:0"
        } else {
            host
        }

        val doubleColon = body.contains("::")
        if (doubleColon && body.count { it == ':' } < 2) return false
        if (!doubleColon && body.count { it == ':' } != 7) return false

        fun isHexGroup(g: String): Boolean =
            g.length in 1..4 && g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        if (doubleColon) {
            // The "::" compression must account for exactly two colons, so no
            // empty single-colon groups (e.g. ":::") can slip through.
            val left = body.substringBefore("::")
            val right = body.substringAfter("::")
            val compressed =
                body.count { it == ':' } - left.count { it == ':' } - right.count { it == ':' }
            if (compressed != 2) return false
            val leftGroups = if (left.isEmpty()) emptyList() else left.split(':')
            val rightGroups = if (right.isEmpty()) emptyList() else right.split(':')
            if (leftGroups.any { !isHexGroup(it) } || rightGroups.any { !isHexGroup(it) }) return false
            return leftGroups.size + rightGroups.size < 8
        }

        val groups = body.split(':')
        return groups.size == 8 && groups.all { isHexGroup(it) }
    }
}
