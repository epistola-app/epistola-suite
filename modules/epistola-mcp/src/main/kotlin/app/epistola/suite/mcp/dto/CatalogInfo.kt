// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.Catalog
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
    /** AUTHORED (editable in this tenant) or SUBSCRIBED (read-only mirror of remote source). */
    val type: String,
    val sourceUrl: String?,
    val installedReleaseVersion: String?,
    val installedAt: OffsetDateTime?,
    /**
     * Current version label: latest released SemVer (AUTHORED) or installed
     * version (SUBSCRIBED). Null when an AUTHORED catalog was never released.
     */
    val releasedVersion: String?,
    /**
     * Content fingerprint identifying the catalog's content independently of
     * the version label. Null when never released.
     */
    val fingerprint: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(catalog: Catalog): CatalogInfo {
            val authored = catalog.type.name == "AUTHORED"
            return CatalogInfo(
                id = catalog.id.value,
                name = catalog.name,
                description = catalog.description,
                attributes = catalog.portableMetadata.attributes.map {
                    CatalogAttributeInfo(catalog = it.catalog, key = it.key, value = it.value)
                },
                keywords = catalog.portableMetadata.keywords,
                presentation = catalog.portableMetadata.presentation?.let {
                    CatalogPresentationInfo(iconAssetSlug = it.iconAssetSlug, imageAssetSlugs = it.imageAssetSlugs)
                },
                license = catalog.portableMetadata.license?.let {
                    CatalogLicenseInfo(it.name, it.spdxExpression, it.url, it.copyrightText)
                },
                type = catalog.type.name,
                sourceUrl = catalog.sourceUrl,
                installedReleaseVersion = catalog.installedReleaseVersion,
                installedAt = catalog.installedAt,
                releasedVersion = if (authored) catalog.releasedVersion else catalog.installedReleaseVersion,
                fingerprint = if (authored) catalog.releasedFingerprint else catalog.installedFingerprint,
                createdAt = catalog.createdAt,
                updatedAt = catalog.updatedAt,
            )
        }
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
