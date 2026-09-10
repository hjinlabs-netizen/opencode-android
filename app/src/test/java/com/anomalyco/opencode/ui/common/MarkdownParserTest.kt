package com.anomalyco.opencode.ui.common

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-parser coverage for the dependency-free markdown renderer. */
class MarkdownParserTest {

    @Test
    fun `splits headings bullets paragraphs and fenced code`() {
        val blocks = parseMarkdownBlocks(
            """
            # Başlık

            - ilk madde
            - ikinci madde

            normal metin
            devamı

            ```
            val x = 1
            println(x)
            ```
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Başlık"),
                MdBlock.Bullet("ilk madde"),
                MdBlock.Bullet("ikinci madde"),
                MdBlock.Paragraph("normal metin\ndevamı"),
                MdBlock.Code("val x = 1\nprintln(x)"),
            ),
            blocks,
        )
    }

    @Test
    fun `unterminated fence still yields a code block`() {
        val blocks = parseMarkdownBlocks("giriş\n```\nbroken")
        assertEquals(
            listOf(MdBlock.Paragraph("giriş"), MdBlock.Code("broken")),
            blocks,
        )
    }

    @Test
    fun `inline bold and code spans annotate plain text`() {
        val annotated = inlineSpan("kuyruk **kalın** ve `kod` var")
        assertEquals("kuyruk kalın ve kod var", annotated.text)

        val bold = annotated.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("kalın", annotated.text.substring(bold.start, bold.end))

        val code = annotated.spanStyles.first { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("kod", annotated.text.substring(code.start, code.end))
    }

    @Test
    fun `unterminated inline markers degrade to literal text`() {
        val annotated = inlineSpan("a **b `c")
        assertEquals("a **b `c", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun `empty source yields no blocks`() {
        assertEquals(emptyList<MdBlock>(), parseMarkdownBlocks(""))
    }
}
