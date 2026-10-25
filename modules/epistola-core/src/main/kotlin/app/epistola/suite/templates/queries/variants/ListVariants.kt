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
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.VARIANT_COLUMNS
import app.epistola.suite.templates.VARIANT_ORDER
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.templates.templateIn
import app.epistola.suite.templates.templateJoin
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListVariants(
    val templateId: TemplateId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<List<TemplateVariant>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class ListVariantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListVariants, List<TemplateVariant>> {
    override fun handle(query: ListVariants): List<TemplateVariant> = jdbi.withHandle<List<TemplateVariant>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT $VARIANT_COLUMNS
                FROM current_template_variants tv
                WHERE tv.tenant_key = :tenantId AND tv.catalog_id = ${catalogAt("tenantId", "catalogKey")}
                  AND tv.template_resource_id = ${templateIn("tenantId", "catalogKey", "templateId")}
                $VARIANT_ORDER
                """,
        )
            .bind("templateId", query.templateId.key)
            .bind("tenantId", query.templateId.tenantKey)
            .bindCatalog("catalogKey", query.templateId.catalogId)
            .mapTo<TemplateVariant>()
            .list()
    }
}
