package com.anomalyco.opencode.ui.common

import com.anomalyco.opencode.R
import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.util.LocaleResourceStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Localization contract tests for the centralized error mapper:
 *
 *  1. every renderable [OpenCodeError] shape maps to a real `R.string`
 *     resource (exhaustive `when` already guarantees compile-time coverage;
 *     this guarantees the resource actually exists in BOTH locales),
 *  2. [OpenCodeError.ServerNarrative] deliberately bypasses resources and
 *     carries the server text through,
 *  3. format arguments match what each resource expects.
 */
class ErrorStringTest {

    private val allRenderableErrors: List<OpenCodeError> = buildList {
        add(OpenCodeError.NoServer)
        add(OpenCodeError.AuthRejected)
        OpenCodeError.NetworkKind.entries.forEach { add(OpenCodeError.Network(it)) }
        add(OpenCodeError.Http(500, "body"))
        add(OpenCodeError.Http(404, null)) // dedicated not-found text
        add(OpenCodeError.Http(429, null)) // dedicated rate-limit text
        add(OpenCodeError.EndpointMissing("/fs/list", "text/html"))
        add(OpenCodeError.EndpointMissing("/fs/list", null))
        add(OpenCodeError.ResponseTooLarge(4L * 1024 * 1024))
        OpenCodeError.InvalidReason.entries.forEach { add(OpenCodeError.InvalidInput(it)) }
        add(OpenCodeError.Unexpected(RuntimeException("debug only")))
    }

    /** R.string field name for a resource id (AGP keeps the names in R). */
    private fun resName(id: Int): String? =
        R.string::class.java.fields.firstOrNull { it.getInt(null) == id }?.name

    @Test
    fun `every renderable error maps to a distinct-named string resource`() {
        val names = allRenderableErrors.map { error ->
            val id = error.messageRes()
            assertTrue("error $error maps to no resource", id != 0)
            val name = resName(id = id)
            assertNotNull("resource id $id has no R.string name", name)
            name!!
        }
        // 404/429 get dedicated texts distinct from the generic Http one.
        assertTrue(names.contains("error_http"))
        assertTrue(names.contains("error_http_not_found"))
        assertTrue(names.contains("error_http_rate_limited"))
        assertEquals(
            "each Network kind must map to its own resource",
            OpenCodeError.NetworkKind.entries.size,
            names.count { it.startsWith("error_network_") },
        )
        assertEquals(
            "each InvalidInput reason must map to its own resource",
            OpenCodeError.InvalidReason.entries.size,
            names.count { it.startsWith("error_invalid_") },
        )
    }

    @Test
    fun `every mapped resource exists in both the English default and Turkish locale`() {
        allRenderableErrors.forEach { error ->
            val name = resName(id = error.messageRes())!!
            assertTrue("$name missing from values/strings.xml", LocaleResourceStrings.hasEntry("values", name))
            assertTrue("$name missing from values-tr", LocaleResourceStrings.hasEntry("values-tr", name))
        }
    }

    @Test
    fun `no user-facing error text is authored in Kotlin anymore`() {
        // Structural guard for the Sprint-1a rule: the mapper must be the
        // single source of prose. ServerNarrative has no resource (0) and
        // renders the server text verbatim.
        assertEquals(0, OpenCodeError.ServerNarrative("raw server text").messageRes())
        assertTrue(OpenCodeError.ServerNarrative("x").messageArgs().isEmpty())
    }

    @Test
    fun `format arguments are carried for the parameterized resources only`() {
        assertEquals(
            listOf<Any>(500),
            OpenCodeError.Http(500, "ignored").messageArgs(),
        )
        assertEquals(
            listOf<Any>("/fs/list", "text/html"),
            OpenCodeError.EndpointMissing("/fs/list", "text/html").messageArgs(),
        )
        assertEquals(
            listOf<Any>("/fs/list", "-"),
            OpenCodeError.EndpointMissing("/fs/list", null).messageArgs(),
        )
        assertEquals(
            listOf<Any>(4),
            OpenCodeError.ResponseTooLarge(4L * 1024 * 1024).messageArgs(),
        )
        assertTrue(OpenCodeError.NoServer.messageArgs().isEmpty())
    }
}
