package io.github.lucasd1as.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTextTest {
    @Test
    fun findsAnAddressInSharedText() {
        assertEquals(
            "https://example.com/files/a.zip",
            UrlText.first("Check this out: https://example.com/files/a.zip, it's great."),
        )
    }

    @Test
    fun findsEverySchemeOnceInOrder() {
        val text = "http://a.test/x ftp://b.test/y https://a.test/x http://a.test/x"
        assertEquals(listOf("http://a.test/x", "ftp://b.test/y", "https://a.test/x"), UrlText.all(text))
    }

    @Test
    fun keepsBracketsThatBelongToTheAddress() {
        assertEquals("https://en.wikipedia.org/wiki/Rust_(language)", UrlText.first("see https://en.wikipedia.org/wiki/Rust_(language)"))
        assertEquals("https://example.com/a", UrlText.first("(https://example.com/a)"))
    }

    @Test
    fun ignoresTextWithoutAddresses() {
        assertNull(UrlText.first("no links here, just words"))
        assertNull(UrlText.first("magnet:?xt=urn:btih:abc"))
        assertNull(UrlText.first("http://"))
    }
}
