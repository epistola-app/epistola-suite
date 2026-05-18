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
}
