package com.anomalyco.opencode.data

/**
 * Minimal thread-safe LRU map with access-order eviction, used for the
 * endpoint-probe memoizations (SSE event paths, file endpoints). Replaces
 * unbounded `ConcurrentHashMap`s whose keys (server base URLs) grew without
 * any ceiling over a long-lived process.
 *
 * [removeEldestEntry] drops the least-recently-read/written entry whenever
 * the map exceeds [maxEntries]; every operation runs under the instance
 * monitor so the map's invariant holds across threads.
 */
class BoundedCache<K : Any, V : Any>(private val maxEntries: Int) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: K): V? = map.remove(key)

    @Synchronized
    fun clear() = map.clear()

    @get:Synchronized
    val size: Int get() = map.size
}
