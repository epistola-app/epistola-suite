package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.VersionSummary
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class ListVersions(
    val variantId: VariantId,
) : Query<List<VersionSummary>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class ListVersionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListVersions, List<VersionSummary>> {
    override fun handle(query: ListVersions): List<VersionSummary> = jdbi.withHandle<List<VersionSummary>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT ver.id, ver.tenant_key, ver.variant_key, ver.status, ver.created_at, ver.published_at, ver.archived_at, ver.contract_version
                FROM template_versions ver
                JOIN template_variants tv ON tv.tenant_key = ver.tenant_key AND tv.catalog_key = ver.catalog_key AND tv.template_key = ver.template_key AND tv.id = ver.variant_key
                WHERE ver.variant_key = :variantId
                  AND ver.tenant_key = :tenantId
                  AND ver.catalog_key = :catalogKey
                  AND tv.template_key = :templateId
                ORDER BY
                    CASE ver.status WHEN 'draft' THEN 0 ELSE 1 END,
                    ver.id DESC
                """,
        )
            .bind("variantId", query.variantId.key)
            .bind("templateId", query.variantId.templateKey)
            .bind("tenantId", query.variantId.tenantKey)
            .bind("catalogKey", query.variantId.catalogKey)
            .mapTo<VersionSummary>()
            .list()
    }
}
