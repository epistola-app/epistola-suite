// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * A table row's actions are labelled. An icon-only button carries its meaning in a tooltip, which
 * touch screens never show and nobody reads at a glance; the catalogs list had a floppy disk that
 * meant "publish". Dialog close buttons and other chrome outside a row's actions cell are not rows.
 */
class RowActionLabelTest {

    @Test
    fun `row actions are labelled, not icon-only`() {
        // A table row's actions cell, and a variant card's footer: the two places a list offers per-item actions.
        val cell = Regex("""<td\b[^>]*class="[^"]*\bactions\b[^"]*"[^>]*>(.*?)</td>|<div class="variant-card-footer">(.*?)<div class="variant-no-matches"""", RegexOption.DOT_MATCHES_ALL)
        val iconOnly = Regex("""<(?:a|button)\b[^>]*class="[^"]*\bep-btn-icon\b[^"]*"""")
        val violations = RepoSources.uiAssetFiles().filter { it.toString().endsWith(".html") }.flatMap { path ->
            val html = RepoSources.stripHtmlComments(Files.readString(path))
            cell.findAll(html).flatMap { iconOnly.findAll(it.groupValues[1] + it.groupValues[2]) }
                .map { "${RepoSources.relativize(path)}: ${it.value.take(120)}" }.toList()
        }
        assertTrue(violations.isEmpty(), "Icon-only row actions; give each a visible label:\n" + violations.joinToString("\n"))
    }
}
