// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Count bound of the per-resource fingerprint cache; there are a handful of bundled catalogs. */
private const val MAX_CACHED_CLASSPATH_CATALOGS: Long = 64

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
    // Per-resource fingerprints of a classpath source are a pure function of bundle content, which
    // cannot change while this JVM runs, yet every tenant that installs the bundled catalog
    // canonicalised and hashed all of its resources again (most of what RegisterCatalog costs per
    // tenant). Cached per manifest URL with a small count bound; there are a handful of bundled
    // catalogs. file: and HTTP sources are computed every time, as before.
    private val classpathResourceFingerprints = Caffeine.newBuilder()
        .maximumSize(MAX_CACHED_CLASSPATH_CATALOGS)
        .build<String, Map<String, String>>()

    fun fingerprint(tenantKey: TenantKey, catalogKey: CatalogKey): String = canonicalizer.fingerprint(contentBuilder.build(tenantKey, catalogKey))

    fun evaluate(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        expected: String?,
    ): CatalogFingerprintEvaluation {
        val content = contentBuilder.build(tenantKey, catalogKey)
        return CatalogFingerprintEvaluation(
            current = canonicalizer.fingerprint(content),
            matchesExpected = expected != null && canonicalizer.matchesFingerprint(content, expected),
        )
    }

    fun fingerprint(content: CatalogContent): String = canonicalizer.fingerprint(content)

    fun matchesFingerprint(content: CatalogContent, expected: String): Boolean = canonicalizer.matchesFingerprint(content, expected)

    fun requirePublishable(content: CatalogContent) = canonicalizer.requirePublishable(content)

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
        if (manifestUrl.startsWith("classpath:")) {
            return classpathResourceFingerprints.get(manifestUrl) {
                computePerResourceFingerprintsFromSource(it, authType, credential)
            }
        }
        return computePerResourceFingerprintsFromSource(manifestUrl, authType, credential)
    }

    private fun computePerResourceFingerprintsFromSource(manifestUrl: String, authType: AuthType, credential: String?): Map<String, String> {
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

data class CatalogFingerprintEvaluation(
    val current: String,
    val matchesExpected: Boolean,
)
