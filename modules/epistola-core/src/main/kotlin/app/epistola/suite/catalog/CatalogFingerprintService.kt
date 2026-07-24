// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Computes the deterministic content fingerprint of a catalog — from the live
 * working copy (DB) or from a source URL (classpath / file / https). The
 * canonicalization itself lives in [CatalogCanonicalizer]; this component wires
 * it to the DB ([CatalogContentBuilder]) and the source fetcher
 * ([CatalogClient]).
 *
 * See [`docs/catalog-versioning.md`](../../../../../../../../docs/catalog-versioning.md).
 */
@Component
class CatalogFingerprintService(
    objectMapper: ObjectMapper,
    private val contentBuilder: CatalogContentBuilder,
    private val catalogClient: CatalogClient,
) {
    private val canonicalizer = CatalogCanonicalizer(objectMapper)

    /** Fingerprint of the live working copy of a catalog. */
    fun fingerprint(tenantKey: TenantKey, catalogKey: CatalogKey): String = canonicalizer.fingerprint(contentBuilder.build(tenantKey, catalogKey))

    fun fingerprint(content: CatalogContent): String = canonicalizer.fingerprint(content)

    /**
     * Fingerprint of a catalog fetched from a source URL. Used to verify the
     * committed fingerprint of bundled catalogs has not drifted.
     */
    fun fingerprintFromSource(manifestUrl: String, authType: AuthType, credential: String?): String = canonicalizer.fingerprintFromSource(catalogClient, manifestUrl, authType, credential)

    /**
     * Per-resource digests of a catalog fetched from a source URL — same fetch
     * loop as [fingerprintFromSource], so a CHANGED verdict is exactly a
     * whole-catalog fingerprint mismatch localized to one resource. Captured at
     * register/upgrade as the stored baseline and re-computed for the incoming
     * release at preview time (source-vs-source, no install round-trip noise).
     */
    fun perResourceFingerprintsFromSource(manifestUrl: String, authType: AuthType, credential: String?): Map<String, String> {
        val manifest = catalogClient.fetchManifest(manifestUrl, authType, credential)
        val detailBytes = LinkedHashMap<String, ByteArray>()
        for (entry in manifest.resources) {
            detailBytes["${entry.type}/${entry.slug}"] =
                catalogClient.fetchBinaryContent(entry.detailUrl, manifestUrl, authType, credential)
        }
        return canonicalizer.perResourceFingerprintsFromSerializedDetails(detailBytes) { contentUrl ->
            catalogClient.fetchBinaryContent(contentUrl, manifestUrl, authType, credential)
        }
    }
}
