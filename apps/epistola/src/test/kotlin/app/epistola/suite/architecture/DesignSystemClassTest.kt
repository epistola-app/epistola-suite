// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A class that is not in the stylesheets renders as unstyled text, and nothing says so — the page
 * still loads, the markup still looks deliberate in review, and the element is simply plain. That
 * has happened twice: eight `alert` variants across the Exchange pages, and the organise browser's
 * success message, which spent its whole life as ordinary paragraph text because `ep-alert-success`
 * was invented rather than looked up.
 *
 * The prefix is the trap. `ep-btn`, `ep-input` and `ep-panel` are all real, so `ep-text-muted` and
 * `ep-alert-success` read as though they must be too; the actual names are `text-muted` and
 * `alert alert-success`.
 *
 * Scoped to the families where the mistake is invisible rather than every class in the repo:
 * layout and utility classes are checked by eye, but a missing severity class looks fine.
 */
@Tag("unit")
class DesignSystemClassTest {

    @Test
    fun `every severity and text class used in markup is defined in the stylesheets`() {
        val defined = definedClasses()
        val offenders = sourceFiles().flatMap { file ->
            val text = file.readText()
            classAttribute.findAll(text)
                .flatMap { it.groupValues[1].split(Regex("\\s+")) }
                .filter { it.isNotBlank() && GUARDED.any { family -> it.startsWith(family) } }
                .filterNot { it in defined }
                .map { "${RepoSources.relativize(file)}: $it" }
                .toList()
        }.distinct().sorted()

        assertThat(offenders)
            .withFailMessage(
                "These classes are not defined in any stylesheet, so they render as plain text:%n%s",
                offenders.joinToString("\n"),
            )
            .isEmpty()
    }

    private companion object {
        /**
         * Families whose absence is silent. A missing `alert-error` still renders the sentence, so
         * only the colour and icon go missing — exactly the kind of thing review does not catch.
         */
        val GUARDED = listOf("alert", "text-", "ep-alert", "ep-text-", "badge")

        val classAttribute = Regex("""class="([^"$]*)"""")
        val classDefinition = Regex("""\.([a-zA-Z][\w-]*)""")

        fun definedClasses(): Set<String> = walk(".css") { true }
            .flatMap { classDefinition.findAll(it.readText()).map { match -> match.groupValues[1] } }
            .toSet()

        /** Markup that ships to the browser: Thymeleaf templates and the Lit components. */
        fun sourceFiles(): List<Path> = walk(".html") { "/src/main/resources/templates/" in it } +
            walk(".ts") { "/src/main/typescript/" in it }

        fun walk(extension: String, accept: (String) -> Boolean): List<Path> = listOf("apps", "modules")
            .map(RepoSources.repoRoot::resolve)
            .filter(Files::isDirectory)
            .flatMap { base ->
                Files.walk(base).use { stream ->
                    stream
                        .filter { it.name.endsWith(extension) }
                        .filter { path ->
                            val relative = RepoSources.relativize(path)
                            "/build/" !in relative &&
                                "/node_modules/" !in relative &&
                                "/dist/" !in relative &&
                                (extension == ".css" || accept(relative))
                        }
                        .toList()
                }
            }
    }
}
