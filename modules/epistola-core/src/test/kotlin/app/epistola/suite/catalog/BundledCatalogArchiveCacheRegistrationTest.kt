// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.testing.BundledCatalogArchiveCacheConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import kotlin.test.assertContains

/**
 * The test-only bundled-catalog archive cache is registered as an auto-configuration through its
 * `.imports` file. That file carries a license header as `#` comments; this holds that Spring still
 * reads the registration under it, so the cache cannot drop out silently and slow every tenant.
 */
class BundledCatalogArchiveCacheRegistrationTest {

    @Test
    fun `the bundled catalog archive cache is registered as an auto-configuration`() {
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).toList()
        assertContains(candidates, BundledCatalogArchiveCacheConfiguration::class.java.name)
    }
}
