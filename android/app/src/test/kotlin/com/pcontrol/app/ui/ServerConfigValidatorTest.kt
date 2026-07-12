package com.pcontrol.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4 tests: pure server-configuration validation.
 *
 * Written BEFORE `validateServerConfiguration` / [ServerConfigError] /
 * [ValidationResult] exist, so this must fail to compile / fail as expected.
 */
class ServerConfigValidatorTest {

    @Test
    fun blankUrlRejected() {
        assertEquals(ServerConfigError.URL_BLANK, validate("", "token").error)
    }

    @Test
    fun whitespaceOnlyUrlRejected() {
        assertEquals(ServerConfigError.URL_BLANK, validate("   ", "token").error)
    }

    @Test
    fun blankTokenRejected() {
        assertEquals(ServerConfigError.TOKEN_BLANK, validate("https://example.com", "").error)
    }

    @Test
    fun whitespaceOnlyTokenRejected() {
        assertEquals(ServerConfigError.TOKEN_BLANK, validate("https://example.com", "   ").error)
    }

    @Test
    fun surroundingWhitespaceTrimmedAndAccepted() {
        val r = validate("  https://example.com  ", "  abc123  ")
        assertTrue(r.isOk)
        assertEquals("https://example.com", r.cleanedUrl)
        assertEquals("abc123", r.cleanedToken)
    }

    @Test
    fun trailingSlashTrimmed() {
        val r = validate("https://example.com/", "abc123")
        assertTrue(r.isOk)
        assertEquals("https://example.com", r.cleanedUrl)
    }

    @Test
    fun malformedUrlRejected() {
        val r = validate("not a url", "abc")
        assertFalse(r.isOk)
        assertTrue(
            r.error == ServerConfigError.URL_BAD_SCHEME ||
                r.error == ServerConfigError.URL_NO_HOST
        )
    }

    @Test
    fun nonHttpSchemeRejected() {
        assertEquals(ServerConfigError.URL_BAD_SCHEME, validate("ftp://example.com", "abc").error)
    }

    @Test
    fun fileSchemeRejected() {
        assertEquals(ServerConfigError.URL_BAD_SCHEME, validate("file:///etc/hosts", "abc").error)
    }

    @Test
    fun noSchemeRejected() {
        val r = validate("example.com", "abc")
        assertFalse(r.isOk)
        assertTrue(
            r.error == ServerConfigError.URL_BLANK || r.error == ServerConfigError.URL_BAD_SCHEME
        )
    }

    @Test
    fun hostlessHttpRejected() {
        assertEquals(ServerConfigError.URL_NO_HOST, validate("https://", "abc").error)
    }

    @Test
    fun hostlessHttpWithPathRejected() {
        assertEquals(ServerConfigError.URL_NO_HOST, validate("http:///path", "abc").error)
    }

    @Test
    fun queryRejected() {
        assertEquals(
            ServerConfigError.URL_QUERY_OR_FRAGMENT,
            validate("https://example.com?x=1", "abc").error
        )
    }

    @Test
    fun fragmentRejected() {
        assertEquals(
            ServerConfigError.URL_QUERY_OR_FRAGMENT,
            validate("https://example.com#top", "abc").error
        )
    }

    @Test
    fun validHttpsAccepted() {
        val r = validate("https://pcontrol.example.com", "token")
        assertTrue(r.isOk)
        assertEquals("https://pcontrol.example.com", r.cleanedUrl)
    }

    @Test
    fun validHttpLanAccepted() {
        val r = validate("http://192.168.1.10:7285", "token")
        assertTrue(r.isOk)
        assertEquals("http://192.168.1.10:7285", r.cleanedUrl)
    }

    @Test
    fun validHttpsWithPathAccepted() {
        val r = validate("https://example.com/pcontrol", "token")
        assertTrue(r.isOk)
        assertEquals("https://example.com/pcontrol", r.cleanedUrl)
    }

    @Test
    fun okResultHasNoError() {
        val ok = validate("https://example.com", "supersecret")
        assertTrue(ok.isOk)
        assertNull(ok.error)
        assertEquals("supersecret", ok.cleanedToken)
    }

    private fun validate(url: String, token: String): ValidationResult =
        validateServerConfiguration(url, token)
}