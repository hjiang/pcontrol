package com.pcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BrowserDomainCacheTest {

    private lateinit var cache: BrowserDomainCache

    @Before
    fun setUp() {
        cache = BrowserDomainCache()
    }

    @Test
    fun `update stores domain`() {
        cache.update("com.android.chrome", "youtube.com")
        assertEquals("youtube.com", cache.get("com.android.chrome"))
    }

    @Test
    fun `null domain does not overwrite`() {
        cache.update("com.android.chrome", "youtube.com")
        cache.update("com.android.chrome", null)
        assertEquals("youtube.com", cache.get("com.android.chrome"))
    }

    @Test
    fun `clear removes domain`() {
        cache.update("com.android.chrome", "youtube.com")
        cache.clear("com.android.chrome")
        assertNull(cache.get("com.android.chrome"))
    }

    @Test
    fun `different browsers isolated`() {
        cache.update("com.android.chrome", "youtube.com")
        cache.update("org.mozilla.firefox", "example.org")
        assertEquals("youtube.com", cache.get("com.android.chrome"))
        assertEquals("example.org", cache.get("org.mozilla.firefox"))
    }

    @Test
    fun `empty cache returns null`() {
        assertNull(cache.get("com.android.chrome"))
    }

    @Test
    fun `clear one does not affect others`() {
        cache.update("com.android.chrome", "youtube.com")
        cache.update("org.mozilla.firefox", "example.org")
        cache.clear("com.android.chrome")
        assertNull(cache.get("com.android.chrome"))
        assertEquals("example.org", cache.get("org.mozilla.firefox"))
    }
}
