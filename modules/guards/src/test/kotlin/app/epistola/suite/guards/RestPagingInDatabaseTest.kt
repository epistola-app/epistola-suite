// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A REST list is paged in the database: a `…Page` query answers the page and its total
 * (`app.epistola.suite.common.Paging`). Fetching the whole list and cutting the page out in the
 * controller makes every page cost the whole table, so the REST module may not slice a list at all.
 */
class RestPagingInDatabaseTest {
    private val banned = Regex("""\.(subList|drop|take|chunked|windowed)\(|\bpaginate\(""")

    @Test
    fun `REST controllers never cut a page out of a fetched list`() {
        val files = RepoSources.mainKotlinFiles().filter { RepoSources.relativize(it).startsWith("modules/rest-api/") }
        assertTrue(files.size > 10, "rest-api sources not found — the scan would pass vacuously")

        val offenders = files
            .filter { banned.containsMatchIn(RepoSources.stripComments(Files.readString(it))) }
            .map(RepoSources::relativize)

        assertEquals(emptyList(), offenders, "Page in SQL with a …Page query (common/Paging.kt), not by slicing a list")
    }
}
