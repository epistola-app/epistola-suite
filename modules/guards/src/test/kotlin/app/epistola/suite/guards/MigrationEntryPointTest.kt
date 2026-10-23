// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * The Helm chart's migration Job starts an image with `EPISTOLA_MIGRATION_MODE=migrate` and no
 * profiles. Every entry point that runs the suite's application hands that to `MigrationLauncher`
 * first, which migrates in an isolated context and exits. One that does not boots the whole
 * application instead, which refuses to start without an authentication profile, so the Job fails
 * after migrating (the `-demo` image did, until October 2026).
 */
class MigrationEntryPointTest {

    @Test
    fun `every entry point running the suite's application starts migrate mode through the launcher`() {
        val entryPoints = RepoSources.mainKotlinFiles().filter { path ->
            val source = RepoSources.stripComments(Files.readString(path))
            "fun main(" in source && "runApplication<" in source && "EpistolaSuiteApplication" in source
        }
        assertTrue(entryPoints.isNotEmpty(), "No entry point found that runs the suite's application")
        val violations = entryPoints.filterNot { "MigrationLauncher.requested(" in RepoSources.stripComments(Files.readString(it)) }
        assertTrue(violations.isEmpty(), "Entry points not handing migrate mode to MigrationLauncher:\n" + violations.joinToString("\n") { RepoSources.relativize(it) })
    }
}
