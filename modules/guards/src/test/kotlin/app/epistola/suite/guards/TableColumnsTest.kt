// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * A fixed-layout table's `<colgroup>` has one `<col>` per header cell. A `<col>` left behind when a
 * column is removed keeps its width, and the table stops short of the page's edge.
 */
class TableColumnsTest {

    @Test
    fun `every colgroup has one col per header cell`() {
        val violations = RepoSources.uiAssetFiles().filter { it.toString().endsWith(".html") }.flatMap { path ->
            val html = RepoSources.stripHtmlComments(Files.readString(path))
            Regex("""<table\b.*?</table>""", RegexOption.DOT_MATCHES_ALL).findAll(html).mapNotNull { table ->
                val colgroup = Regex("""<colgroup>(.*?)</colgroup>""", RegexOption.DOT_MATCHES_ALL).find(table.value) ?: return@mapNotNull null
                val header = Regex("""<thead\b[^>]*>\s*<tr\b[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL).find(table.value) ?: return@mapNotNull null
                val cols = Regex("""<col\b""").findAll(colgroup.groupValues[1]).count()
                val cells = Regex("""<th\b""").findAll(header.groupValues[1]).count()
                if (cols == cells) null else "${RepoSources.relativize(path)}: $cols <col> for $cells header cells"
            }.toList()
        }
        assertTrue(violations.isEmpty(), "Tables whose colgroup does not match their header:\n" + violations.joinToString("\n"))
    }
}
