// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.CodeListDto
import app.epistola.api.model.CodeListEntryDto
import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource

/**
 * Map domain `CodeList` → public `CodeListDto`. Hides the internal `CLASSPATH`
 * source type (only used by bundled-catalog imports) — over the REST API,
 * tenants only see `INLINE` or `URL`. Anything else is collapsed to `INLINE`
 * for now; this matches the catalog-protocol view where classpath-sourced
 * lists arrive as inline rows in the import path.
 */
internal fun CodeList.toDto() = CodeListDto(
    slug = slug.value,
    tenantId = tenantKey.value,
    catalog = catalogKey.value,
    displayName = displayName,
    sourceType = when (sourceType) {
        CodeListSource.URL -> CodeListDto.SourceType.URL
        // INLINE + CLASSPATH (bundled catalogs) both surface as INLINE here.
        // CLASSPATH is suite-internal and never appears for tenant-authored
        // code lists; it shows up only after a catalog import, where the
        // tenant sees the materialized inline entries.
        CodeListSource.INLINE -> CodeListDto.SourceType.INLINE
        CodeListSource.CLASSPATH -> CodeListDto.SourceType.INLINE
    },
    authType = when (authType) {
        app.epistola.suite.catalog.AuthType.NONE -> CodeListDto.AuthType.NONE
        app.epistola.suite.catalog.AuthType.API_KEY -> CodeListDto.AuthType.API_KEY
        app.epistola.suite.catalog.AuthType.BEARER -> CodeListDto.AuthType.BEARER
    },
    // `catalogType` is loaded via the JOIN in `GetCodeList`/`ListCodeLists`,
    // so for any row that exists it's non-null. Defaulting to AUTHORED is
    // the safe fallback for the (theoretical) orphan case.
    catalogType = when (catalogType) {
        app.epistola.suite.catalog.CatalogType.SUBSCRIBED -> CodeListDto.CatalogType.SUBSCRIBED
        else -> CodeListDto.CatalogType.AUTHORED
    },
    readOnly = catalogType == app.epistola.suite.catalog.CatalogType.SUBSCRIBED,
    createdAt = createdAt,
    lastModified = updatedAt,
    description = description,
    sourceUrl = sourceUrl,
    lastRefreshedAt = lastRefreshedAt,
    lastRefreshError = lastRefreshError,
)

internal fun CodeListEntry.toDto() = CodeListEntryDto(
    code = code,
    label = label,
    sortOrder = sortOrder,
    hidden = hidden,
)

internal fun CodeListEntryDto.toModel() = CodeListEntry(
    code = code,
    label = label,
    sortOrder = sortOrder ?: 0,
    hidden = hidden ?: false,
)
