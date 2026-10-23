// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Page content stays inside its content column. Breaking out of it with a viewport-sized negative
 * margin (`margin-left: -48vw` with `left: 50%`) assumes the column is centred in the window, which
 * it is not beside the workspace's side menu: the content then lies over the menu. A wide table
 * fills the column and scrolls sideways within it instead (`overflow-x: auto`).
 */
class NoViewportBreakoutTest {

    @Test
    fun `no page content breaks out of its column with a negative viewport margin`() {
        val violations = RepoSources.uiAssetFiles().flatMap { path ->
            BREAKOUT.findAll(RepoSources.stripHtmlComments(Files.readString(path)))
                .map { "${RepoSources.relativize(path)}: ${it.value}" }
                .toList()
        }
        assertTrue(violations.isEmpty(), "Content breaking out of its column (it overlaps the side menu):\n" + violations.joinToString("\n"))
    }

    private companion object {
        val BREAKOUT = Regex("""margin(-left|-right|-inline-start|-inline-end)?\s*:\s*-\s*[\d.]+vw""")
    }
}
