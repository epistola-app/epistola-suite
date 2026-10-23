// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries.variants

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.templateAtAddress
import app.epistola.suite.templates.templateJoin
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListVariants(
    val templateId: TemplateId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
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
                SELECT tv.id, tv.tenant_key, c.catalog_key, template.id AS template_key, tv.title, tv.description, tv.attributes, tv.is_default, tv.created_at, tv.updated_at, tv.created_by, tv.updated_by, tv.revision
                FROM template_variants tv
                ${templateJoin("tv")}
                JOIN catalogs c ON c.tenant_key = template.tenant_key AND c.catalog_id = template.catalog_id
                WHERE tv.tenant_key = :tenantId AND tv.version_id = ${versionAt("tenantId", "catalogKey")}
                  AND tv.template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
                ORDER BY tv.is_default DESC, tv.created_at ASC
                """,
        )
            .bind("templateId", query.templateId.key)
            .bind("tenantId", query.templateId.tenantKey)
            .bind("catalogKey", query.templateId.catalogKey)
            .mapTo<TemplateVariant>()
            .list()
    }
}
