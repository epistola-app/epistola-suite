package app.epistola.suite.templates.commands.versions

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.VersionNotPublishedException
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Archives a published version.
 * Only published versions can be archived.
 * Archived versions are immutable and kept for historical purposes.
 *
 * Throws if the version doesn't exist, is not published, or the tenant doesn't own the template.
 */
data class ArchiveVersion(
    val versionId: VersionId,
) : Command<TemplateVersion>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_PUBLISH
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class ArchiveVersionHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ArchiveVersion, TemplateVersion> {
    override fun handle(command: ArchiveVersion): TemplateVersion {
        requireCatalogEditable(command.versionId.tenantKey, command.versionId.catalogKey)
        return jdbi.inTransaction<TemplateVersion, Exception> { handle ->
            // Check version exists and its status
            val versionRow = handle.createQuery(
                """
                SELECT status FROM template_versions
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey
                  AND template_key = :templateId AND variant_key = :variantId AND id = :versionId
                """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("templateId", command.versionId.templateKey)
                .bind("variantId", command.versionId.variantKey)
                .bind("versionId", command.versionId.key)
                .mapTo<String>()
                .findOne()
                .orElse(null)
                ?: throw VersionNotFoundException(
                    command.versionId.tenantKey,
                    command.versionId.templateKey,
                    command.versionId.variantKey,
                    command.versionId.key,
                )

            if (versionRow != "published") {
                throw VersionNotPublishedException(command.versionId.tenantKey, command.versionId.key)
            }

            // Check if the version is still active in any environment
            val activeEnvironments = handle.createQuery(
                """
                SELECT environment_key
                FROM environment_activations
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey
                  AND template_key = :templateId AND variant_key = :variantId AND version_key = :versionId
                """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("templateId", command.versionId.templateKey)
                .bind("variantId", command.versionId.variantKey)
                .bind("versionId", command.versionId.key)
                .mapTo<String>()
                .list()
                .map { EnvironmentKey.of(it) }

            if (activeEnvironments.isNotEmpty()) {
                throw VersionStillActiveException(
                    versionId = command.versionId.key,
                    variantId = command.versionId.variantKey,
                    activeEnvironments = activeEnvironments,
                )
            }

            // Archive the version
            handle.createQuery(
                """
                UPDATE template_versions
                SET status = 'archived', archived_at = NOW()
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey
                  AND template_key = :templateId AND variant_key = :variantId AND id = :versionId
                  AND status = 'published'
                RETURNING *
                """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("templateId", command.versionId.templateKey)
                .bind("variantId", command.versionId.variantKey)
                .bind("versionId", command.versionId.key)
                .mapTo<TemplateVersion>()
                .one()
        }
    }
}
