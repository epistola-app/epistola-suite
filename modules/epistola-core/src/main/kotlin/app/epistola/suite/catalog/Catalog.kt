// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogPresentation
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.crypto.Secret
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
    /** Encrypted at rest via the JDBI [Secret] mappers; plaintext only in memory. */
    val sourceAuthCredential: Secret? = null,
    val installedReleaseVersion: String? = null,
    val installedFingerprint: String? = null,
    /**
     * Per-resource source-side digests (`"type/slug"` -> SHA-256) of the
     * installed release, captured from the source manifest at register/upgrade.
     * The source-vs-source baseline for [PreviewCatalogUpgrade][app.epistola.suite.catalog.queries.PreviewCatalogUpgrade].
     * Never publisher-authored; null for AUTHORED catalogs.
     */
    @Json val installedResourceFingerprints: Map<String, String>? = null,
    @Json val catalogMetadata: CatalogMetadata = CatalogMetadata(),
    val installedAt: OffsetDateTime? = null,
    val releasedVersion: String? = null,
    val releasedFingerprint: String? = null,
    val releasedAt: OffsetDateTime? = null,
    /**
     * When catalog content was last set wholesale by a ZIP import. With
     * [releasedAt] it forms the AUTHORED drift baseline
     * `GREATEST(releasedAt, importedAt)`: a resource changed after it = the
     * working copy has unreleased changes (the catalog list's "pending
     * changes" hint). A no-op re-import advances this in lockstep with the
     * imported resources, so it does not register as drift.
     */
    val importedAt: OffsetDateTime? = null,
    val contentUpdatedAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

/** Catalog metadata that is part of the portable catalog manifest. */
data class CatalogMetadata(
    val attributes: List<AttributeAssignment> = emptyList(),
    val keywords: Set<String> = emptySet(),
    val presentation: CatalogPresentation? = null,
    val license: CatalogLicense? = null,
) {
    fun toCatalogInfo(
        slug: String,
        name: String,
        description: String?,
        availableAssetSlugs: Set<String>? = null,
    ): CatalogInfo {
        val effectivePresentation = if (availableAssetSlugs == null) {
            presentation
        } else {
            presentation?.let {
                CatalogPresentation(
                    iconAssetSlug = it.iconAssetSlug?.takeIf(availableAssetSlugs::contains),
                    imageAssetSlugs = it.imageAssetSlugs.filter(availableAssetSlugs::contains),
                )
            }
        }
        return CatalogInfo.create(
            slug = slug,
            name = name,
            description = description,
            attributes = attributes,
            keywords = keywords,
            presentation = effectivePresentation,
            license = license,
        )
    }

    companion object {
        fun from(info: CatalogInfo): CatalogMetadata = CatalogMetadata(
            attributes = info.attributes,
            keywords = info.keywords,
            presentation = info.presentation,
            license = info.license,
        )
    }
}

enum class CatalogType {
    AUTHORED,
    SUBSCRIBED,
}

enum class AuthType {
    NONE,
    API_KEY,
    BEARER,
}
