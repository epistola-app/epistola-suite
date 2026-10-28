// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.queries

import app.epistola.suite.attributes.ATTRIBUTE_COLUMNS
import app.epistola.suite.attributes.AttributeRows
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.Paging
import app.epistola.suite.common.ResultPage
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListAttributeDefinitions(
    val tenantId: TenantId,
    val catalog: CatalogId? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<List<VariantAttributeDefinition>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantId == tenantId) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

/** [ListAttributeDefinitions], one page of it, cut in the database. */
data class ListAttributeDefinitionPage(
    val tenantId: TenantId,
    val catalog: CatalogId? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    val page: PageRequest,
) : ReleaseReadable,
    Query<ResultPage<VariantAttributeDefinition>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantId == tenantId) { "The catalog is of the tenant" }
    }
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = tenantId.key
}

@Component
class ListAttributeDefinitionsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) : QueryHandler<ListAttributeDefinitions, List<VariantAttributeDefinition>> {
    override fun handle(query: ListAttributeDefinitions): List<VariantAttributeDefinition> = jdbi.withHandle<List<VariantAttributeDefinition>, Exception> { handle ->
        AttributeList.query(handle, query.tenantId, query.catalog, paged = false)
            .map(AttributeRows.of(handle, objectMapper, query.tenantId.key))
            .list()
    }
}

@Component
class ListAttributeDefinitionPageHandler(
    private val jdbi: Jdbi,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) : QueryHandler<ListAttributeDefinitionPage, ResultPage<VariantAttributeDefinition>> {
    override fun handle(query: ListAttributeDefinitionPage): ResultPage<VariantAttributeDefinition> = jdbi.withHandle<ResultPage<VariantAttributeDefinition>, Exception> { handle ->
        Paging.read(query.page, AttributeRows.of(handle, objectMapper, query.tenantId.key)) {
            AttributeList.query(handle, query.tenantId, query.catalog, paged = true)
        }
    }
}

/**
 * The attributes of a tenant, or of one of its catalogs, by name; [paged] selects one page of them.
 * A draft or a release, read the same way (ADR 0033).
 */
private object AttributeList {
    fun query(handle: Handle, tenantId: TenantId, catalog: CatalogId?, paged: Boolean): org.jdbi.v3.core.statement.Query {
        val sql = buildString {
            append("SELECT $ATTRIBUTE_COLUMNS")
            if (paged) append(", ${Paging.TOTAL}")
            append(" FROM current_attributes t WHERE t.tenant_key = :t")
            if (catalog != null) append(" AND t.catalog_id = ${catalogAt("t", "c")}")
            append(" ORDER BY t.display_name ASC, t.catalog_id, t.id")
            if (paged) append(" ${Paging.LIMIT_OFFSET}")
        }
        val q = handle.createQuery(sql).bind("t", tenantId.key)
        if (catalog != null) q.bindCatalog("c", catalog)
        return q
    }
}
