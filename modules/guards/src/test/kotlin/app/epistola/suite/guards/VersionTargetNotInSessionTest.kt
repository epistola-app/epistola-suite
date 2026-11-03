// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Nothing reads the catalog version a statement means from the session (ADR 0049): the read model
 * holds every version's content (`version_themes`, …), and a statement names the version it reads by
 * its `version_id`.
 *
 * They once read transaction-local settings instead (`epistola.content_version`,
 * `epistola.release_catalog`, `epistola.release_version`), so the same SQL read a different version
 * depending on the transaction it ran in. This keeps them out of every migration and every statement.
 */
class VersionTargetNotInSessionTest {

    @Test
    fun `no migration or statement reads or sets the version a caller means through the session`() {
        val files = RepoSources.mainSqlFiles() + RepoSources.mainKotlinFiles()
        assertTrue(files.any { it.toString().endsWith("__core_2_0_read_model.sql") }, "The scan found no migrations — it is broken")
        val violations = files.flatMap { path ->
            Files.readAllLines(path).mapIndexedNotNull { index, line ->
                SESSION.find(line)?.let { "${RepoSources.relativize(path)}:${index + 1}: ${it.value}" }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "These read or set the version a statement means through a session setting. Bind the version it " +
                "reads instead (bindVersion in CatalogSql.kt):\n" + violations.joinToString("\n"),
        )
    }

    private companion object {
        val SESSION = Regex("""(current_setting|set_config)\(\s*'epistola\.(content_version|release_catalog|release_version)'""")
    }
}
