// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * A table row's actions are labelled, outlined buttons. An icon-only button carries its meaning in a
 * tooltip, which touch screens never show and nobody reads at a glance; the catalogs list had a floppy
 * disk that meant "publish". A ghost button has no border and reads as plain text beside the row's
 * values. Dialog close buttons and other chrome outside a row's actions cell are not rows.
 */
class RowActionLabelTest {

    /** A table row's actions cell, and a variant card's footer: the two places a list offers per-item actions. */
    private val actions = Regex(
        """<td\b[^>]*class="[^"]*\bactions\b[^"]*"[^>]*>(.*?)</td>|<div class="variant-card-footer">(.*?)<div class="variant-no-matches"""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** The buttons in every row's actions matching [button], as `file: markup`. */
    private fun rowButtons(button: Regex): List<String> = RepoSources.uiAssetFiles().filter { it.toString().endsWith(".html") }.flatMap { path ->
        val html = RepoSources.stripHtmlComments(Files.readString(path))
        actions.findAll(html).flatMap { button.findAll(it.groupValues[1] + it.groupValues[2]) }
            .map { "${RepoSources.relativize(path)}: ${it.value.take(120)}" }.toList()
    }

    @Test
    fun `row actions are labelled, not icon-only`() {
        val violations = rowButtons(Regex("""<(?:a|button)\b[^>]*class="[^"]*\bep-btn-icon\b[^"]*""""))
        assertTrue(violations.isEmpty(), "Icon-only row actions; give each a visible label:\n" + violations.joinToString("\n"))
    }

    @Test
    fun `row actions are outlined, not ghost`() {
        val violations = rowButtons(Regex("""<(?:a|button)\b[^>]*class="[^"]*\bep-btn-ghost\b[^"]*""""))
        assertTrue(violations.isEmpty(), "Ghost row actions read as plain text; use ep-btn-outline:\n" + violations.joinToString("\n"))
    }
}
