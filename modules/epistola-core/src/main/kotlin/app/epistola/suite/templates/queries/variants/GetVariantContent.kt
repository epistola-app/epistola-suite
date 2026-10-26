// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries.variants

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.TemplateModels
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.templateIn
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * The working model of a variant, as stored: stencil instances by reference. Null when there is no
 * such variant. Readers that render or check it expand the instances themselves.
 */
data class GetVariantContent(
    val variantId: VariantId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<TemplateDocument?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class GetVariantContentHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetVariantContent, TemplateDocument?> {
    override fun handle(query: GetVariantContent): TemplateDocument? = jdbi.withHandle<TemplateDocument?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT template_model::text FROM current_template_variants
            WHERE tenant_key = :t AND id = :v AND catalog_id = ${catalogAt("t", "c")} AND template_resource_id = ${templateIn("t", "c", "k")}
            """,
        )
            .bind("t", query.variantId.tenantKey)
            .bindCatalog("c", query.variantId.catalogId)
            .bind("k", query.variantId.templateKey)
            .bind("v", query.variantId.key)
            .mapTo(String::class.java)
            .findOne()
            .map { TemplateModels.read(it, query.variantId.catalogKey.value, objectMapper) }
            .orElse(null)
    }
}

/** A variant's working model as it is read, with the variant's revision read in the same statement. */
data class VariantContentAtRevision(val templateModel: TemplateDocument, val revision: Long)

/**
 * [GetVariantContent] for a caller that may write it back: the model and the revision it was read
 * at, together, so the revision cannot belong to a later write than the model.
 */
data class GetVariantContentAtRevision(
    val variantId: VariantId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<VariantContentAtRevision?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class GetVariantContentAtRevisionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetVariantContentAtRevision, VariantContentAtRevision?> {
    override fun handle(query: GetVariantContentAtRevision): VariantContentAtRevision? = jdbi.withHandle<VariantContentAtRevision?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT template_model::text AS template_model, revision FROM current_template_variants
            WHERE tenant_key = :t AND id = :v AND catalog_id = ${catalogAt("t", "c")} AND template_resource_id = ${templateIn("t", "c", "k")}
            """,
        )
            .bind("t", query.variantId.tenantKey)
            .bindCatalog("c", query.variantId.catalogId)
            .bind("k", query.variantId.templateKey)
            .bind("v", query.variantId.key)
            .map { rs, _ -> VariantContentAtRevision(TemplateModels.read(rs.getString("template_model"), query.variantId.catalogKey.value, objectMapper), rs.getLong("revision")) }
            .findOne()
            .orElse(null)
    }
}
