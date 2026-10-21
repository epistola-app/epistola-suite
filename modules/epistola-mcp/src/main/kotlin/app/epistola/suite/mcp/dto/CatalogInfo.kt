// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.queries.CatalogEditability
import java.time.OffsetDateTime

/**
 * Catalog within the current tenant. Templates, themes, and stencils are
 * scoped to a catalog; AI tools that operate on those resources need a
 * `catalogId` argument and discover available catalogs through `list_catalogs`.
 */
data class CatalogInfo(
    /** Catalog key (slug). Used as `catalogId` arg to other tools. */
    val id: String,
    val name: String,
    val description: String?,
    val attributes: List<CatalogAttributeInfo>,
    val keywords: Set<String>,
    val presentation: CatalogPresentationInfo?,
    val license: CatalogLicenseInfo?,
    /**
     * Whether the catalog can be changed here (ADR 0032): drafts started, resources created and
     * changed, releases cut. Where its releases come from does not decide it.
     */
    val editable: Boolean,
    /** Why it cannot be changed here, in a sentence; null when it can. */
    val readOnlyReason: String?,
    /** Where its releases come from (an Exchange, manifest or `classpath:` URI); null for one only released here or imported from files. */
    val source: String?,
    val installedReleaseVersion: String?,
    val installedAt: OffsetDateTime?,
    /** Its highest release, released here or received; null when it has none. */
    val releasedVersion: String?,
    /**
     * Content fingerprint of [releasedVersion], identifying the content independently of the
     * version label. Null when it has no release.
     */
    val fingerprint: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(catalog: Catalog, editability: CatalogEditability): CatalogInfo = CatalogInfo(
            id = catalog.id.value,
            name = catalog.name,
            description = catalog.description,
            attributes = catalog.catalogMetadata.attributes.map {
                CatalogAttributeInfo(catalog = it.catalogKey, key = it.key, value = it.value)
            },
            keywords = catalog.catalogMetadata.keywords,
            presentation = catalog.catalogMetadata.presentation?.let {
                CatalogPresentationInfo(iconAssetSlug = it.iconImageKey, imageAssetSlugs = it.imageKeys)
            },
            license = catalog.catalogMetadata.license?.let {
                CatalogLicenseInfo(it.name, it.spdxExpression, it.url, it.copyrightText)
            },
            editable = editability.editable,
            readOnlyReason = editability.reason?.explanation,
            source = catalog.sourceUrl,
            installedReleaseVersion = catalog.installedReleaseVersion,
            installedAt = catalog.installedAt,
            releasedVersion = catalog.highestReleaseVersion,
            fingerprint = catalog.highestReleaseFingerprint,
            createdAt = catalog.createdAt,
            updatedAt = catalog.updatedAt,
        )
    }
}

data class CatalogAttributeInfo(val catalog: String, val key: String, val value: String)

data class CatalogPresentationInfo(val iconAssetSlug: String?, val imageAssetSlugs: List<String>)

data class CatalogLicenseInfo(
    val name: String,
    val spdxExpression: String?,
    val url: String?,
    val copyrightText: String?,
)
