// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.testing.BundledCatalogFingerprints
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The `system` catalog is core's: every tenant gets it at creation, so it ships in this module's
 * main resources and its fingerprint is asserted here.
 *
 * The demo catalog used to be asserted alongside it. It now ships in `apps/epistola-demo`, and
 * `DemoCatalogFingerprintTest` there covers it — see
 * [BundledCatalogFingerprints] for the shared machinery and the regeneration steps.
 */
@Tag("unit")
class BundledCatalogFingerprintTest {

    @Test
    fun `system catalog committed fingerprint matches its content`() {
        BundledCatalogFingerprints.assertFingerprintMatches("classpath:epistola/catalogs/system/catalog.json")
    }
}
