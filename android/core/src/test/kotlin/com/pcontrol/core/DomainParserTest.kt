package com.pcontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DomainParserTest {

    // --- plain domains ---

    @Test
    fun `simple domain`() {
        assertEquals("youtube.com", DomainParser.parse("https://youtube.com/watch?v=123"))
    }

    @Test
    fun `subdomain`() {
        assertEquals("youtube.com", DomainParser.parse("https://m.youtube.com/watch?v=123"))
    }

    @Test
    fun `multi level subdomain`() {
        assertEquals("youtube.com", DomainParser.parse("https://music.youtube.com/watch?v=123"))
    }

    // --- country-code TLDs ---

    @Test
    fun `uk co uk domain`() {
        assertEquals("example.co.uk", DomainParser.parse("https://www.example.co.uk/page"))
    }

    @Test
    fun `org uk domain`() {
        assertEquals("example.org.uk", DomainParser.parse("https://sub.example.org.uk/path"))
    }

    // --- edge cases ---

    @Test
    fun `non url text returns null`() {
        assertNull(DomainParser.parse("just some text"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(DomainParser.parse(""))
    }

    @Test
    fun `ip address returns the ip`() {
        assertEquals("93.184.216.34", DomainParser.parse("http://93.184.216.34/path"))
    }

    @Test
    fun `ipv6 address returns the ip`() {
        assertEquals("::1", DomainParser.parse("http://[::1]/path"))
    }

    @Test
    fun `port in url`() {
        assertEquals("example.com", DomainParser.parse("https://example.com:8080/path"))
    }

    @Test
    fun `path and query`() {
        assertEquals("google.com", DomainParser.parse("https://www.google.com/search?q=test&hl=en"))
    }

    @Test
    fun `fragment`() {
        assertEquals("example.com", DomainParser.parse("https://example.com/page#section"))
    }

    @Test
    fun `no scheme`() {
        assertEquals("example.com", DomainParser.parse("example.com/path"))
    }

    @Test
    fun `no www prefix`() {
        assertEquals("example.com", DomainParser.parse("http://example.com"))
    }

    @Test
    fun `www prefix stripped`() {
        assertEquals("example.com", DomainParser.parse("http://www.example.com"))
    }

    @Test
    fun `m dot prefix preserved for registrable domain`() {
        // "m." is a subdomain, registrable domain is still example.com
        assertEquals("example.com", DomainParser.parse("http://m.example.com"))
    }

    // --- tricky cases ---

    @Test
    fun `url bar mid typing incomplete url`() {
        assertNull(DomainParser.parse("exam"))
    }

    @Test
    fun `single word search query`() {
        assertNull(DomainParser.parse("cats"))
    }

    @Test
    fun `url without dot in host`() {
        assertNull(DomainParser.parse("http://localhost/path"))
    }

    // --- title-like text must never be accepted as a domain ---

    @Test
    fun `title with colon and whitespace returns null`() {
        assertNull(DomainParser.parse("CNN: Breaking news"))
    }

    @Test
    fun `title with dots and whitespace returns null`() {
        assertNull(DomainParser.parse("Amazon.com. Spend less. Smile more."))
    }

    @Test
    fun `single colon pseudo ip returns null`() {
        assertNull(DomainParser.parse("cnn:breaking"))
    }

    // --- colon/dot garbage without whitespace (Copilot review follow-up) ---

    @Test
    fun `multi colon title returns null`() {
        assertNull(DomainParser.parse("cnn:breaking:news"))
    }

    @Test
    fun `single colon with dot title returns null`() {
        assertNull(DomainParser.parse("CNN:Breaking.news"))
    }

    @Test
    fun `unterminated bracket is not an ip`() {
        assertNull(DomainParser.parse("[::1"))
    }

    // --- IPv6 must keep working (validator must not be over-tight) ---

    @Test
    fun `multi group ipv6 still parses`() {
        assertEquals("2001:db8::1", DomainParser.parse("http://[2001:db8::1]/"))
    }

    @Test
    fun `link local ipv6 still parses`() {
        assertEquals("fe80::1", DomainParser.parse("http://[fe80::1]/"))
    }

    // --- Copilot review round on PR #77 ---

    @Test
    fun `ipv4 embedded ipv6 tail parses`() {
        assertEquals("::ffff:192.0.2.128", DomainParser.parse("http://[::ffff:192.0.2.128]/"))
    }

    @Test
    fun `nested brackets are not an ip`() {
        assertNull(DomainParser.parse("[[::1]]"))
    }

    @Test
    fun `non ascii digits are not hex`() {
        assertNull(DomainParser.parse("http://[١::1]/"))
    }

    @Test
    fun `non ascii digits are not a full form ipv6`() {
        assertNull(DomainParser.parse("http://[٠:٠:٠:٠:٠:٠:٠:٠]/"))
    }
}
