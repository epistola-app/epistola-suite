// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.testing.BundledCatalogFingerprints
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The demo catalog ships in this app, so its fingerprint is asserted here.
 *
 * `DemoLoader` installs it by fingerprint rather than by version string, so editing the catalog
 * without updating `release.fingerprint` means the change never reaches a tenant that already has
 * it. See [BundledCatalogFingerprints] for the regeneration steps.
 *
 * Not to be confused with `modules/epistola-core/src/test/resources/epistola/catalogs/fixture/`,
 * which is a deliberately frozen copy used by core's catalog tests and asserted by nothing.
 */
@Tag("unit")
class DemoCatalogFingerprintTest {

    @Test
    fun `demo catalog committed fingerprint matches its content`() {
        BundledCatalogFingerprints.assertFingerprintMatches("classpath:epistola/catalogs/demo/catalog.json")
    }
}
