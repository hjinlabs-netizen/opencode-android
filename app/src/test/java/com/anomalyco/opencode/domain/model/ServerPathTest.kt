package com.anomalyco.opencode.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W.1: pure relative-path handling for the server file endpoints, per the
 * W.0 spike contract - root is ".", `..` and absolute paths are never
 * produced, `\` and `/` are equivalent separators with the input's own
 * style preserved.
 */
class ServerPathTest {

    // ---- Windows-style paths -------------------------------------------------
    @Test
    fun `parentOf walks Windows paths level by level`() {
        assertEquals("Desktop", ServerPath.parentOf("Desktop\\Folder"))
        assertEquals("a\\b", ServerPath.parentOf("a\\b\\c"))
        assertEquals(ServerPath.ROOT, ServerPath.parentOf("Desktop"))
        assertNull("root has no parent", ServerPath.parentOf(ServerPath.ROOT))
    }

    @Test
    fun `normalize collapses Windows separators and trailing slashes`() {
        assertEquals("Desktop", ServerPath.normalize("Desktop\\"))
        assertEquals("Desktop", ServerPath.normalize("Desktop\\\\\\"))
        assertEquals("Desktop\\Folder", ServerPath.normalize("Desktop\\\\Folder\\\\"))
        assertEquals("Desktop\\Folder", ServerPath.normalize("Desktop\\Folder\\"))
    }

    @Test
    fun `normalize resolves dot and dotdot segments so traversal is never emitted`() {
        assertEquals("Desktop", ServerPath.normalize(".\\Desktop\\.\\"))
        assertEquals(ServerPath.ROOT, ServerPath.normalize(".."))
        assertEquals(ServerPath.ROOT, ServerPath.normalize("Desktop\\.."))
        assertEquals("b", ServerPath.normalize("a\\..\\b"))
        assertEquals(ServerPath.ROOT, ServerPath.normalize("a\\b\\..\\.."))
        // parentOf output can never contain a `..` segment.
        assertEquals("a", ServerPath.parentOf("a\\b"))
    }

    // ---- POSIX-style and mixed separators -----------------------------------
    @Test
    fun `parentOf and normalize handle forward-slash servers unchanged`() {
        assertEquals("Desktop", ServerPath.parentOf("Desktop/Folder"))
        assertEquals("a/b", ServerPath.parentOf("a/b/c"))
        assertEquals("Desktop/Folder", ServerPath.normalize("Desktop/Folder"))
        assertEquals("Desktop", ServerPath.normalize("Desktop/"))
    }

    @Test
    fun `mixed separators normalize to the first separator style seen`() {
        assertEquals("Desktop/Folder/Sub", ServerPath.normalize("Desktop/Folder\\Sub"))
        assertEquals("Desktop\\Folder\\Sub", ServerPath.normalize("Desktop\\Folder/Sub"))
    }

    // ---- root and empty handling --------------------------------------------
    @Test
    fun `root and blank inputs normalize to the project root`() {
        assertEquals(ServerPath.ROOT, ServerPath.normalize(""))
        assertEquals(ServerPath.ROOT, ServerPath.normalize("   "))
        assertEquals(ServerPath.ROOT, ServerPath.normalize("."))
        assertEquals(ServerPath.ROOT, ServerPath.normalize("./"))
        assertEquals(ServerPath.ROOT, ServerPath.normalize(".\\\\"))
    }

    @Test
    fun `isRoot recognizes every root spelling only`() {
        assertTrue(ServerPath.isRoot("."))
        assertTrue(ServerPath.isRoot(""))
        assertTrue(ServerPath.isRoot("  "))
        assertTrue(ServerPath.isRoot("./"))
        assertFalse(ServerPath.isRoot("Desktop"))
        assertFalse(ServerPath.isRoot("Desktop/Folder"))
    }

    // ---- join ----------------------------------------------------------------
    @Test
    fun `join appends without duplicate separators`() {
        assertEquals("Desktop\\Folder", ServerPath.join("Desktop", "Folder"))
        assertEquals("Desktop\\Folder", ServerPath.join("Desktop\\", "\\Folder"))
        assertEquals("Desktop\\Folder", ServerPath.join("Desktop", "Folder/"))
        assertEquals("a/b/c", ServerPath.join("a/b", "c")) // root style wins
    }

    @Test
    fun `join with root or blank yields the other side`() {
        assertEquals("Desktop\\X", ServerPath.join(".", "Desktop\\X"))
        assertEquals("Desktop", ServerPath.join("Desktop", "."))
        assertEquals("Desktop", ServerPath.join("Desktop", "  "))
        assertEquals("Desktop", ServerPath.join("", "Desktop"))
    }

    @Test
    fun `join never produces traversal even from hostile child input`() {
        assertEquals("Desktop\\Folder", ServerPath.join("Desktop", "..\\Folder"))
        assertEquals("Desktop", ServerPath.join("Desktop", ".."))
    }

    // ---- algebraic sanity -----------------------------------------------------
    @Test
    fun `normalize is idempotent and parentOf never grows a path`() {
        val samples = listOf(
            ".", "", "Desktop", "Desktop\\", "a/b", "a\\\\b\\\\c\\",
            ".\\x\\..\\y", "Desktop/Folder\\Sub",
        )
        samples.forEach { sample ->
            val once = ServerPath.normalize(sample)
            assertEquals("idempotence for '$sample'", once, ServerPath.normalize(once))
            ServerPath.parentOf(sample)?.let { parent ->
                assertTrue("parent '$parent' must be shorter than '$once'", parent.length < once.length || parent == ServerPath.ROOT)
            }
        }
    }
}
