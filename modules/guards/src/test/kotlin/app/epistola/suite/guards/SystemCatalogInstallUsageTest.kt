// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tenant always has its system catalog. `CreateTenant.installSystemCatalog = false` leaves it out,
 * for the tests only: `SystemCatalogTemplate` (modules/testing) creates test tenants without it and
 * copies it from a template tenant (docs/testing.md). Production code that leaves it out would create
 * tenants missing reserved attributes, code lists and fonts until the next start installed them.
 */
class SystemCatalogInstallUsageTest {
    private val leavesItOut = Regex("""\binstallSystemCatalog\s*=\s*false\b""")
    private val allowed = "SystemCatalogTemplate.kt"

    @Test
    fun `only the tests' system catalog template creates a tenant without its system catalog`() {
        val files = RepoSources.mainKotlinFiles()
        assertTrue(files.any { it.name == allowed }, "$allowed not found — the scan would pass vacuously")

        val offenders = files
            .filter { it.name != allowed }
            .filter { leavesItOut.containsMatchIn(RepoSources.stripComments(Files.readString(it))) }
            .map(RepoSources::relativize)

        assertEquals(emptyList(), offenders, "Create a tenant with its system catalog; only SystemCatalogTemplate leaves it out")
    }
}
