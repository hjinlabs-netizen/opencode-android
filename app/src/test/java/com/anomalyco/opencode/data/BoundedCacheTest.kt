package com.anomalyco.opencode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** LRU semantics of the bounded probe-memoization cache. */
class BoundedCacheTest {

    @Test
    fun `put and get round-trip`() {
        val cache = BoundedCache<String, String>(maxEntries = 2)
        cache["a"] = "1"
        assertEquals("1", cache["a"])
        assertNull(cache["missing"])
    }

    @Test
    fun `inserting past the cap evicts the eldest entry`() {
        val cache = BoundedCache<String, String>(maxEntries = 2)
        cache["a"] = "1"
        cache["b"] = "2"
        cache["c"] = "3"
        assertNull("`a` was the least recently used", cache["a"])
        assertEquals("2", cache["b"])
        assertEquals("3", cache["c"])
        assertEquals(2, cache.size)
    }

    @Test
    fun `reads refresh recency so hot keys survive eviction`() {
        val cache = BoundedCache<String, String>(maxEntries = 2)
        cache["a"] = "1"
        cache["b"] = "2"
        assertSame("1", cache["a"]) // touch `a` -> `b` becomes eldest
        cache["c"] = "3"
        assertEquals("1", cache["a"])
        assertNull(cache["b"])
        assertEquals("3", cache["c"])
    }

    @Test
    fun `remove and clear drop entries and size tracks the cap`() {
        val cache = BoundedCache<String, Int>(maxEntries = 4)
        cache["x"] = 1
        cache["y"] = 2
        assertEquals(2, cache.size)
        cache.remove("x")
        assertNull(cache["x"])
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero-capacity is rejected`() {
        BoundedCache<String, String>(maxEntries = 0)
    }
}
