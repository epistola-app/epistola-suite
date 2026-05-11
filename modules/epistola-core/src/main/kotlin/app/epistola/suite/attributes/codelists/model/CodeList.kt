package app.epistola.suite.attributes.codelists.model

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

/**
 * A named collection of {code, label} entries that an attribute can bind to as
 * its value constraint. Tenant + catalog scoped, mirroring the attributes that
 * reference it.
 *
 * Entries are loaded separately via `ListCodeListEntries` to avoid hauling
 * up to a few hundred rows around when the UI just lists code lists.
 */
data class CodeList(
    val slug: CodeListKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val displayName: String,
    val description: String? = null,
    val sourceType: CodeListSource,
    val sourceUrl: String? = null,
    val authType: AuthType = AuthType.NONE,
    val credential: String? = null,
    val lastRefreshedAt: OffsetDateTime? = null,
    val lastRefreshError: String? = null,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
    /**
     * Type of the catalog the code list lives in, populated when the row is
     * read via a JOIN to catalogs. Used by the UI to gate edit affordances.
     * Null when the row is read without the join.
     */
    val catalogType: CatalogType? = null,
)
