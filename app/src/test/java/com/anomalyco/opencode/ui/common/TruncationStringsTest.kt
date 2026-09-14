package com.anomalyco.opencode.ui.common

import com.anomalyco.opencode.util.LocaleResourceStrings
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The truncation booleans added in Sprint 1b must have a localized home in
 * BOTH locales — mirrors the error-string contract for the memory-limit
 * notices (`MessagePartCards` chips, `FileExplorerScreen` preview banner).
 */
class TruncationStringsTest {

    @Test
    fun `truncation notices exist in the English default and Turkish locale`() {
        listOf("content_truncated", "preview_truncated", "diff_truncated").forEach { name ->
            assertTrue("$name missing from values/strings.xml", LocaleResourceStrings.hasEntry("values", name))
            assertTrue("$name missing from values-tr", LocaleResourceStrings.hasEntry("values-tr", name))
        }
    }

    @Test
    fun `folder picker strings exist in both locales`() {
        listOf(
            "picker_browse",
            "picker_choose",
            "picker_project_root",
            "picker_empty",
            "picker_cannot_open",
            "picker_enter_manually",
        ).forEach { name ->
            assertTrue("$name missing from values/strings.xml", LocaleResourceStrings.hasEntry("values", name))
            assertTrue("$name missing from values-tr", LocaleResourceStrings.hasEntry("values-tr", name))
        }
    }
}
