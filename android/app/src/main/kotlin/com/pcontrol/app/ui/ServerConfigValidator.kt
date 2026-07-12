package com.pcontrol.app.ui

import java.net.URI
import java.net.URISyntaxException

/**
 * Stage 4 — pure, Android-free server-configuration validation.
 *
 * Used by the server config dialog so that invalid configuration can never
 * silently save (Section 4 of plan 09, Stage 4). The existing sync client
 * appends its own `/api/v1/sync` endpoint, so the configured URL must not
 * include a query or fragment.
 */
enum class ServerConfigError {
    URL_BLANK,
    URL_BAD_SCHEME,
    URL_NO_HOST,
    URL_QUERY_OR_FRAGMENT,
    TOKEN_BLANK,
}

data class ValidationResult(
    val error: ServerConfigError?,
    /** Cleaned URL for persistence (trimmed + trailing slash removed), valid iff [error] == null. */
    val cleanedUrl: String,
    /** Cleaned token for persistence (trimmed), valid iff [error] == null. */
    val cleanedToken: String,
) {
    val isOk: Boolean get() = error == null
}

/**
 * Validate and normalize a server URL + device token.
 *
 * Rules (Section 4, Stage 4 task 3):
 *  - trim surrounding whitespace from both fields;
 *  - trim all trailing `/` from the URL (preserving a path component);
 *  - URL must be absolute, use `http` or `https`, and have a nonblank host;
 *  - URL must not include a query or fragment;
 *  - token must be nonblank;
 *  - order: token blank is reported only when the URL is otherwise valid, so
 *    the user fixes the URL field first and is not shown two errors at once.
 */
fun validateServerConfiguration(rawUrl: String, rawToken: String): ValidationResult {
    val url = rawUrl.trim()
    val token = rawToken.trim()

    if (url.isEmpty()) {
        return ValidationResult(ServerConfigError.URL_BLANK, url, token)
    }

    val uri: URI = try {
        URI(url)
    } catch (e: URISyntaxException) {
        // "https://" throws "Expected authority" before we ever read host;
        // if the URL already declares an http(s) scheme, the failure is a
        // missing host rather than a bad scheme.
        val isHttpScheme = url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        val err = if (isHttpScheme) ServerConfigError.URL_NO_HOST else ServerConfigError.URL_BAD_SCHEME
        return ValidationResult(err, url, token)
    }

    val scheme = uri.scheme
    val schemeOk = scheme == "http" || scheme == "https"
    // A hostless absolute http(s) URL (e.g. "https://") parses with null host.
    val host = uri.host
    val hostMissing = host.isNullOrBlank()

    if (!schemeOk) {
        // No scheme at all, or non-http scheme → bad scheme.
        return ValidationResult(ServerConfigError.URL_BAD_SCHEME, url, token)
    }
    if (hostMissing) {
        return ValidationResult(ServerConfigError.URL_NO_HOST, url, token)
    }
    if (uri.rawQuery != null || uri.fragment != null) {
        return ValidationResult(ServerConfigError.URL_QUERY_OR_FRAGMENT, url, token)
    }
    if (token.isEmpty()) {
        return ValidationResult(ServerConfigError.TOKEN_BLANK, url, token)
    }

    // Trim all trailing '/' from the authority+path only after every other
    // check passes, so a host-less URL like "https://" is reported as
    // URL_NO_HOST rather than collapsed to "https:" by an eager trim.
    val cleanedUrl = url.trimEnd('/')
    return ValidationResult(null, cleanedUrl, token)
}