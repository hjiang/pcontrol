package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionTest {

    // ── parse ────────────────────────────────────────────────────────

    @Test
    fun `parse strips android-v prefix`() {
        assertEquals("1.2.3", Version.parse("android-v1.2.3"))
    }

    @Test
    fun `parse strips v prefix`() {
        assertEquals("1.2.3", Version.parse("v1.2.3"))
    }

    @Test
    fun `parse plain version unchanged`() {
        assertEquals("1.0.0", Version.parse("1.0.0"))
    }

    @Test
    fun `parse strips android- prefix then v`() {
        assertEquals("2.0.1", Version.parse("android-v2.0.1"))
    }

    @Test
    fun `parse handles android- prefix without v`() {
        assertEquals("1.5.0", Version.parse("android-1.5.0"))
    }

    @Test
    fun `parse returns null for empty string`() {
        assertNull(Version.parse(""))
    }

    @Test
    fun `parse returns null for blank string`() {
        assertNull(Version.parse("  "))
    }

    @Test
    fun `parse returns null for non-semver string`() {
        assertNull(Version.parse("not-a-version"))
    }

    @Test
    fun `parse returns null for missing patch`() {
        assertNull(Version.parse("1.0"))
    }

    @Test
    fun `parse returns null for letters in version`() {
        assertNull(Version.parse("1.0.0-beta"))
    }

    @Test
    fun `parse returns null when segment exceeds Int range`() {
        assertNull(Version.parse("9999999999.0.0"))
        assertNull(Version.parse("1.9999999999.0"))
    }

    // ── compare ──────────────────────────────────────────────────────

    @Test
    fun `compare equal versions returns 0`() {
        assertEquals(0, Version.compare("1.0.0", "1.0.0"))
    }

    @Test
    fun `compare older should be negative`() {
        val result = Version.compare("1.0.0", "1.0.1")
        assertTrue(result != null && result < 0)
    }

    @Test
    fun `compare newer should be positive`() {
        val result = Version.compare("1.0.1", "1.0.0")
        assertTrue(result != null && result > 0)
    }

    @Test
    fun `compare v-prefix treated same`() {
        assertEquals(0, Version.compare("v1.2.3", "1.2.3"))
    }

    @Test
    fun `compare android-v prefix treated same`() {
        assertEquals(0, Version.compare("android-v1.2.3", "1.2.3"))
    }

    @Test
    fun `compare major version bump`() {
        val result = Version.compare("1.9.9", "2.0.0")
        assertTrue(result != null && result < 0)
    }

    @Test
    fun `compare minor version bump`() {
        val result = Version.compare("1.1.9", "1.2.0")
        assertTrue(result != null && result < 0)
    }

    @Test
    fun `compare returns null when either input is malformed`() {
        assertNull(Version.compare("abc", "1.0.0"))
        assertNull(Version.compare("1.0.0", ""))
        assertNull(Version.compare("", ""))
    }

    @Test
    fun `compare returns null on integer overflow segment`() {
        assertNull(Version.compare("9999999999999999.0.0", "1.0.0"))
        assertNull(Version.compare("1.0.0", "1.9999999999999999.0"))
    }

    // ── needsUpdate ───────────────────────────────────────────────────

    @Test
    fun `needsUpdate returns needs_update when newer version available`() {
        assertEquals(UpdateCheckResult.NEEDS_UPDATE, Version.needsUpdate("1.0.0", "1.0.1"))
    }

    @Test
    fun `needsUpdate returns up_to_date when same version`() {
        assertEquals(UpdateCheckResult.UP_TO_DATE, Version.needsUpdate("1.0.0", "1.0.0"))
    }

    @Test
    fun `needsUpdate returns up_to_date when installed is newer`() {
        assertEquals(UpdateCheckResult.UP_TO_DATE, Version.needsUpdate("1.0.1", "1.0.0"))
    }

    @Test
    fun `needsUpdate returns null when version cannot be parsed`() {
        assertNull(Version.needsUpdate("bad", "1.0.0"))
    }
}
