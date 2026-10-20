// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * The actions in a page header (top right) are buttons people see as buttons: filled for the main
 * action, outlined for the others, at the normal size. A ghost button there has no border and reads
 * as a text link beside them.
 */
class PageHeaderActionStyleTest {

    @Test
    fun `page header actions are filled or outlined buttons at the normal size`() {
        val violations = RepoSources.uiAssetFiles().filter { it.toString().endsWith(".html") }.flatMap { path ->
            val html = RepoSources.stripHtmlComments(Files.readString(path))
            Regex("""actions=~\{::([\w-]+)}""").findAll(html).flatMap { ref ->
                val fragment = Regex("""th:fragment="${Regex.escape(ref.groupValues[1])}"(.*?)</th:block>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1).orEmpty()
                Regex("""<(?:a|button)\b[^>]*class="([^"]*\bep-btn\b[^"]*)"""").findAll(fragment)
                    .map { it.groupValues[1] }
                    .filter { "ep-btn-ghost" in it || "ep-btn-sm" in it }
                    .map { "${RepoSources.relativize(path)} (${ref.groupValues[1]}): $it" }
            }.toList()
        }
        assertTrue(violations.isEmpty(), "Page header actions styled as links or small buttons:\n" + violations.joinToString("\n"))
    }
}
