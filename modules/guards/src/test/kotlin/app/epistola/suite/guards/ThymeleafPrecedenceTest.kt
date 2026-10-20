// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Thymeleaf runs `th:replace` and `th:insert` before `th:if` and `th:unless`, so a condition on the
 * same element as a fragment inclusion is never evaluated: the fragment always renders. It reads as
 * deliberate in review and renders without error. The workspace bar's version menu ticked every
 * draft as the current one because of it.
 *
 * The condition goes on a wrapping `th:block` instead.
 */
@Tag("unit")
class ThymeleafPrecedenceTest {

    @Test
    fun `no element puts a condition beside a fragment inclusion`() {
        val offenders = templates().flatMap { file ->
            val text = file.readText()
            TAG.findAll(text)
                .filter { INCLUSION.containsMatchIn(it.value) && CONDITION.containsMatchIn(it.value) }
                .map { "${RepoSources.relativize(file)}:${text.substring(0, it.range.first).count { c -> c == '\n' } + 1}" }
                .toList()
        }.sorted()

        assertThat(offenders)
            .withFailMessage(
                "th:if/th:unless beside th:replace/th:insert is never evaluated; wrap the inclusion in a th:block that carries the condition:%n%s",
                offenders.joinToString("\n"),
            )
            .isEmpty()
    }

    private companion object {
        /** An opening tag, attributes spanning lines, quoted values kept whole. */
        val TAG = Regex("""<(?:th:block|[a-zA-Z][\w-]*)\s(?:[^>"]|"[^"]*")*>""")
        val INCLUSION = Regex("""\sth:(?:replace|insert)=""")
        val CONDITION = Regex("""\sth:(?:if|unless)=""")

        fun templates() = listOf("apps", "modules")
            .map(RepoSources.repoRoot::resolve)
            .filter(Files::isDirectory)
            .flatMap { base ->
                Files.walk(base).use { stream ->
                    stream.filter { it.name.endsWith(".html") }
                        .filter { path ->
                            val relative = RepoSources.relativize(path)
                            "/src/main/resources/templates/" in relative && "/build/" !in relative && "/node_modules/" !in relative
                        }
                        .toList()
                }
            }
    }
}
