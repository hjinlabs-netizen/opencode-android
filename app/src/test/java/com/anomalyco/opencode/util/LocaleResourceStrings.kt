package com.anomalyco.opencode.util

import java.io.File

/**
 * Test-only access to the app's localized string tables. Reads the raw
 * `strings.xml` files from disk so JVM tests can assert resource existence
 * in BOTH the English default and the Turkish locale without Robolectric.
 */
object LocaleResourceStrings {

    private val cache = mutableMapOf<String, String>()

    fun contents(locale: String): String = cache.getOrPut(locale) {
        var dir = File("").absoluteFile
        repeat(4) {
            val candidate = File(dir, "src/main/res/$locale/strings.xml")
            if (candidate.exists()) return@getOrPut candidate.readText()
            dir = dir.parentFile
        }
        error("could not locate $locale/strings.xml from ${File("").absolutePath}")
    }

    fun hasEntry(locale: String, name: String): Boolean =
        Regex("""name="$name"[^>]*>([^<]+)""").containsMatchIn(contents(locale))

    fun hasEntryInAllLocales(name: String): Boolean =
        hasEntry("values", name) && hasEntry("values-tr", name)
}
