package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

data class Catalog(
    val id: CatalogKey,
    val tenantKey: TenantKey,
    val name: String,
    val description: String? = null,
    val type: CatalogType,
    val sourceUrl: String? = null,
    val sourceAuthType: AuthType = AuthType.NONE,
    val sourceAuthCredential: String? = null,
    val installedReleaseVersion: String? = null,
    val installedFingerprint: String? = null,
    /**
     * Per-resource source-side digests (`"type/slug"` -> SHA-256) of the
     * installed release, captured from the source manifest at register/upgrade.
     * The source-vs-source baseline for [PreviewCatalogUpgrade][app.epistola.suite.catalog.queries.PreviewCatalogUpgrade].
     * Never publisher-authored; null for AUTHORED catalogs.
     */
    @Json val installedResourceFingerprints: Map<String, String>? = null,
    val installedAt: OffsetDateTime? = null,
    val releasedVersion: String? = null,
    val releasedFingerprint: String? = null,
    val releasedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

enum class CatalogType {
    AUTHORED,
    SUBSCRIBED,
}

enum class AuthType {
    NONE,
    API_KEY,
    BEARER,
}
