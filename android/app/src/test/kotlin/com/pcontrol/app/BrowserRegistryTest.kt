package com.pcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRegistryTest {

    @Test
    fun `chrome has url bar id`() {
        assertNotNull(BrowserRegistry.urlBarViewId("com.android.chrome"))
    }

    @Test
    fun `firefox has url bar id`() {
        assertNotNull(BrowserRegistry.urlBarViewId("org.mozilla.firefox"))
    }

    @Test
    fun `brave has url bar id`() {
        assertNotNull(BrowserRegistry.urlBarViewId("com.brave.browser"))
    }

    @Test
    fun `edge has url bar id`() {
        assertNotNull(BrowserRegistry.urlBarViewId("com.microsoft.emmx"))
    }

    @Test
    fun `unknown package returns null`() {
        assertNull(BrowserRegistry.urlBarViewId("com.unknown.browser"))
    }

    @Test
    fun `known browser is recognized`() {
        assertTrue(BrowserRegistry.isKnownBrowser("com.android.chrome"))
    }

    @Test
    fun `unknown app is not a browser`() {
        assertFalse(BrowserRegistry.isKnownBrowser("com.game"))
    }

    @Test
    fun `known packages are non empty`() {
        assertTrue(BrowserRegistry.knownPackages().isNotEmpty())
    }

    @Test
    fun `chrome url bar id matches expected`() {
        assertEquals(
            "com.android.chrome:id/url_bar",
            BrowserRegistry.urlBarViewId("com.android.chrome")
        )
    }
}
