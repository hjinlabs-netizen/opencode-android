package com.anomalyco.opencode

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sprint 1b prose guard — the CI-visible enforcement of the Sprint 1a rules:
 *
 *  Rule 1: no Turkish-prose string literals inside the data layer or in any
 *          ViewModel. User-facing text belongs in `values/strings.xml` +
 *          `values-tr/strings.xml` and is rendered through typed errors
 *          (`ui/common/ErrorUi.kt`) or `stringResource` in screens.
 *          (Language-neutral glyphs like the `••••••` token mask pass —
 *          they are not prose.)
 *  Rule 2: message-bearing `Exception("...")` constructors are allowed only
 *          in the error/wire/stream layer, where the text is an English
 *          developer debug hint: `domain/error/`, `data/remote/OpenCodeApi.kt`
 *          and `data/remote/stream/`. Everything else must throw
 *          `OpenCodeException(OpenCodeError.*)`.
 *
 * Heuristics (documented deliberately): comment-leading lines (double-slash
 * or star prefixed) are skipped — the rules target literals, not prose in
 * KDoc; a line containing a double quote before a Turkish letter approximates
 * "string literal" and is exact for the regression class these rules were
 * written for (the hard-coded TR error texts that lived in ApiErrors and the
 * ViewModels until 1a).
 */
class ProseGuardTest {

    private val turkishInQuotes = Regex("\"[^\"]*[şğıöüçŞĞİÖÜÇ]")
    private val messageBearingException = Regex("Exception\\(\"")

    private fun sourceRoot(): File {
        var dir = File("").absoluteFile
        repeat(4) {
            val candidate = File(dir, "src/main/java/com/anomalyco/opencode")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("could not locate the source root from ${File("").absolutePath}")
    }

    private fun isCommentLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
    }

    @Test
    fun `the detectors actually fire on the patterns they forbid`() {
        // Guards against a silently-passing regex rewrite.
        assertTrue(turkishInQuotes.containsMatchIn("""x = Error("Sunucuya bağlanılamadı")"""))
        assertTrue(messageBearingException.containsMatchIn("""throw IllegalStateException("boom")"""))
        // ...and do not fire on the accepted shapes.
        assertTrue(!turkishInQuotes.containsMatchIn("""private const val MASK = "••••••""""))
        assertTrue(!messageBearingException.containsMatchIn("""throw OpenCodeException(OpenCodeError.NoServer)"""))
    }

    @Test
    fun `rule 1 - no turkish prose literals in the data layer or ViewModels`() {
        val root = sourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter {
                val rel = it.relativeTo(root).invariantSeparatorsPath
                rel.startsWith("data/") || (rel.contains("ui/") && it.name.endsWith("ViewModel.kt"))
            }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (!isCommentLine(line) && turkishInQuotes.containsMatchIn(line)) {
                        "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertTrue(
            "Move the text to values/strings.xml + values-tr and surface it via an OpenCodeError kind:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `rule 2 - message-bearing exceptions only inside the error wire stream layer`() {
        val root = sourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot {
                val rel = it.relativeTo(root).invariantSeparatorsPath
                rel.startsWith("domain/error/") ||
                    rel == "data/remote/OpenCodeApi.kt" ||
                    rel.startsWith("data/remote/stream/")
            }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (!isCommentLine(line) && messageBearingException.containsMatchIn(line)) {
                        "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertTrue(
            "Throw OpenCodeException(OpenCodeError.*) instead — ui/common/ErrorUi.kt renders it:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
