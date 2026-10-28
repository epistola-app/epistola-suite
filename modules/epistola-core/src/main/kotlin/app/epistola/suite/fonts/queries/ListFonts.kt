// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.Paging
import app.epistola.suite.common.ResultPage
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.FONT_COLUMNS
import app.epistola.suite.fonts.model.Font
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Lists font families owned by a tenant, optionally filtered to a single
 * catalog.
 *
 * Whether a row can be edited is the owning catalog's editability
 * (`ListCatalogEditability`, ADR 0032), not a field of the row. Variants are
 * not fetched here; use `GetFontVariants` for that.
 */
data class ListFonts(
    val tenantId: TenantId,
    val catalog: CatalogId? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<List<Font>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantId == tenantId) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

/** [ListFonts], one page of it, cut in the database. */
data class ListFontPage(
    val tenantId: TenantId,
    val catalog: CatalogId? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    val page: PageRequest,
) : ReleaseReadable,
    Query<ResultPage<Font>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantId == tenantId) { "The catalog is of the tenant" }
    }
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListFontsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListFonts, List<Font>> {
    override fun handle(query: ListFonts): List<Font> = jdbi.withHandle<List<Font>, Exception> { handle ->
        FontList.query(handle, query.tenantId, query.catalog, paged = false).mapTo<Font>().list()
    }
}

@Component
class ListFontPageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListFontPage, ResultPage<Font>> {
    override fun handle(query: ListFontPage): ResultPage<Font> = jdbi.withHandle<ResultPage<Font>, Exception> { handle ->
        Paging.read(query.page, Font::class.java) { FontList.query(handle, query.tenantId, query.catalog, paged = true) }
    }
}

/** The fonts of a tenant, or of one of its catalogs, by name; [paged] selects one page of them. */
private object FontList {
    fun query(handle: Handle, tenantId: TenantId, catalog: CatalogId?, paged: Boolean): org.jdbi.v3.core.statement.Query {
        val sql = buildString {
            append("SELECT $FONT_COLUMNS")
            if (paged) append(", ${Paging.TOTAL}")
            append(" FROM current_fonts f WHERE f.tenant_key = :tenantId")
            if (catalog != null) append(" AND f.catalog_id = ${catalogAt("tenantId", "catalogKey")}")
            append(" ORDER BY f.name ASC, f.catalog_id, f.slug")
            if (paged) append(" ${Paging.LIMIT_OFFSET}")
        }
        val q = handle.createQuery(sql).bind("tenantId", tenantId.key)
        if (catalog != null) q.bindCatalog("catalogKey", catalog)
        return q
    }
}
