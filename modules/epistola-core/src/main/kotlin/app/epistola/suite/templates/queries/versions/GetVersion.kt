// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.templates.templateAtAddress
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetVersion(
    val versionId: VersionId,
) : Query<TemplateVersion?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class GetVersionHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetVersion, TemplateVersion?> {
    override fun handle(query: GetVersion): TemplateVersion? = jdbi.withHandle<TemplateVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT ver.id, ver.tenant_key, ver.variant_key, ver.template_model, ver.status,
                       ver.created_at, ver.published_at, ver.archived_at,
                       ver.rendering_defaults_version, ver.resolved_theme, ver.contract_version
                FROM template_versions ver
                WHERE ver.id = :versionId
                  AND ver.variant_key = :variantId
                  AND ver.tenant_key = :tenantId
                  AND ver.template_resource_id = ${templateAtAddress("tenantId", "catalogKey", "templateId")}
                """,
        )
            .bind("versionId", query.versionId.key)
            .bind("variantId", query.versionId.variantKey)
            .bind("templateId", query.versionId.templateKey)
            .bind("tenantId", query.versionId.tenantKey)
            .bind("catalogKey", query.versionId.catalogKey)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)
    }
}
