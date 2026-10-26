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
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.VARIANT_COLUMNS
import app.epistola.suite.templates.VARIANT_ORDER
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.templates.templateIn
import app.epistola.suite.templates.templateJoin
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetVariant(
    val variantId: VariantId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<TemplateVariant?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class GetVariantHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetVariant, TemplateVariant?> {
    override fun handle(query: GetVariant): TemplateVariant? = jdbi.withHandle<TemplateVariant?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT $VARIANT_COLUMNS
                FROM current_template_variants tv
                WHERE tv.id = :variantId
                  AND tv.tenant_key = :tenantId AND tv.catalog_id = ${catalogAt("tenantId", "catalogKey")}
                  AND tv.template_resource_id = ${templateIn("tenantId", "catalogKey", "templateId")}
                """,
        )
            .bind("variantId", query.variantId.key)
            .bind("templateId", query.variantId.templateKey)
            .bind("tenantId", query.variantId.tenantKey)
            .bindCatalog("catalogKey", query.variantId.catalogId)
            .mapTo<TemplateVariant>()
            .findOne()
            .orElse(null)
    }
}

data class GetVariantSummaries(
    val templateId: TemplateId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<List<VariantSummary>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class GetVariantSummariesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetVariantSummaries, List<VariantSummary>> {
    override fun handle(query: GetVariantSummaries): List<VariantSummary> = jdbi.withHandle<List<VariantSummary>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT
                    tv.id,
                    tv.title,
                    tv.attributes,
                    tv.is_default,
                    EXISTS (
                        SELECT 1 FROM resource_ready_marks m
                        JOIN resource_content_digests d ON d.tenant_key = m.tenant_key AND d.version_id = m.version_id
                                                         AND d.resource_id = m.resource_id AND d.part = m.part
                        WHERE m.tenant_key = tv.tenant_key AND m.version_id = tv.version_id AND m.resource_id = tv.template_resource_id AND m.part = tv.id AND m.digest = d.digest
                    ) as ready
                FROM current_template_variants tv
                WHERE tv.tenant_key = :tenantId AND tv.catalog_id = ${catalogAt("tenantId", "catalogKey")}
                  AND tv.template_resource_id = ${templateIn("tenantId", "catalogKey", "templateId")}
                $VARIANT_ORDER
                """,
        )
            .bind("templateId", query.templateId.key)
            .bind("tenantId", query.templateId.tenantKey)
            .bindCatalog("catalogKey", query.templateId.catalogId)
            .mapTo<VariantSummary>()
            .list()
    }
}
