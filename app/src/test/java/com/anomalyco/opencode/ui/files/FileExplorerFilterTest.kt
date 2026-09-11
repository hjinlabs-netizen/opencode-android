package com.anomalyco.opencode.ui.files

import com.anomalyco.opencode.domain.model.FileNode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Name-filter rules for the file explorer search box (Sprint B P1). */
class FileExplorerFilterTest {

    private val nodes = listOf(
        FileNode("src", "src", isDirectory = true),
        FileNode("README.md", "README.md", isDirectory = false),
        FileNode("build.gradle.kts", "build.gradle.kts", isDirectory = false),
        FileNode("settings.gradle.kts", "settings.gradle.kts", isDirectory = false),
    )

    @Test
    fun `blank and whitespace queries return everything unchanged`() {
        assertEquals(nodes, filterFileNodes(nodes, ""))
        assertEquals(nodes, filterFileNodes(nodes, "   "))
    }

    @Test
    fun `matches by name case-insensitively`() {
        assertEquals(
            listOf("build.gradle.kts", "settings.gradle.kts"),
            filterFileNodes(nodes, "GRADLE").map { it.name },
        )
    }

    @Test
    fun `directories and files are both matched`() {
        assertEquals(listOf("src"), filterFileNodes(nodes, "SR").map { it.name })
    }

    @Test
    fun `query matches substring not only prefix`() {
        assertEquals(listOf("README.md"), filterFileNodes(nodes, "EADM").map { it.name })
    }

    @Test
    fun `no match yields empty list`() {
        assertEquals(emptyList<FileNode>(), filterFileNodes(nodes, "zzz"))
    }
}
