// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.catalog.outbound.CatalogSourceProperties
import app.epistola.suite.catalog.outbound.OutboundHttpFetcher
import app.epistola.suite.catalog.outbound.OutboundSourceReader
import app.epistola.suite.catalog.outbound.OutboundUrlGuard
import org.assertj.core.api.Assertions.assertThat
import org.springframework.core.io.DefaultResourceLoader
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Asserts that a bundled catalog's committed `release.fingerprint` still matches its content.
 *
 * The loaders detect a changed catalog by fingerprint, not by version string, so a content edit
 * without a matching fingerprint update means the change never reaches an existing tenant.
 *
 * Shared because the bundled catalogs no longer live in one project: `system` belongs to
 * `epistola-core` (every tenant gets it), while the demo catalog ships in `apps/epistola-demo`.
 * Each asserts its own; this is the machinery they have in common.
 *
 * Regenerating after an intentional content change: run the test, read the "actual" value from the
 * failure message, paste it into that catalog's `catalog.json` `release.fingerprint`, and bump
 * `release.version`.
 */
object BundledCatalogFingerprints {

    private val objectMapper = jsonMapper { addModule(kotlinModule()) }

    private val catalogClient = run {
        val properties = CatalogSourceProperties()
        val guard = OutboundUrlGuard(properties)
        CatalogClient(
            sourceReader = OutboundSourceReader(guard, OutboundHttpFetcher(guard), DefaultResourceLoader(), MAX_SOURCE_BYTES),
            schemaMigrator = CatalogSchemaMigrator(),
            properties = properties,
        )
    }

    private const val MAX_SOURCE_BYTES = 20L * 1024 * 1024

    private val canonicalizer = CatalogCanonicalizer(objectMapper)

    fun assertFingerprintMatches(manifestUrl: String) {
        val manifest = catalogClient.fetchManifest(manifestUrl, AuthType.NONE, null)
        val committed = manifest.release.fingerprint
        val actual = canonicalizer.fingerprintFromSource(catalogClient, manifestUrl, AuthType.NONE, null)

        assertThat(actual)
            .describedAs(
                "Bundled catalog '%s' content fingerprint drifted. Set release.fingerprint in %s to: %s",
                manifest.catalog.key,
                manifestUrl,
                actual,
            )
            .isEqualTo(committed)
    }
}
