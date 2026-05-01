package app.epistola.suite.templates.commands.versions

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Creates or updates the draft version for a variant.
 * If no draft exists, creates one. If a draft exists, updates it.
 */
data class UpdateDraft(
    val variantId: VariantId,
    val templateModel: TemplateDocument,
) : Command<TemplateVersion?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class UpdateDraftHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val pathExtractor: app.epistola.suite.templates.analysis.TemplatePathExtractor,
) : CommandHandler<UpdateDraft, TemplateVersion?> {
    override fun handle(command: UpdateDraft): TemplateVersion? {
        requireCatalogEditable(command.variantId.tenantKey, command.variantId.catalogKey)
        return jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
            // Verify the variant belongs to a template owned by the tenant
            val variantExists = handle.createQuery(
                """
                SELECT COUNT(*) > 0
                FROM template_variants
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND id = :variantId AND template_key = :templateId
                """,
            )
                .bind("variantId", command.variantId.key)
                .bind("templateId", command.variantId.templateKey)
                .bind("tenantId", command.variantId.tenantKey)
                .bind("catalogKey", command.variantId.catalogKey)
                .mapTo<Boolean>()
                .one()

            if (!variantExists) {
                return@inTransaction null
            }

            val templateModelJson = objectMapper.writeValueAsString(command.templateModel)
            val referencedPaths = pathExtractor.extractReferencedPaths(command.templateModel)
            val referencedPathsJson = objectMapper.writeValueAsString(referencedPaths)

            // Try to update existing draft first
            val updated = handle.createUpdate(
                """
                UPDATE template_versions
                SET template_model = :templateModel::jsonb, referenced_paths = :referencedPaths::jsonb
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND variant_key = :variantId
                  AND template_key = :templateId
                  AND status = 'draft'
                """,
            )
                .bind("tenantId", command.variantId.tenantKey)
                .bind("catalogKey", command.variantId.catalogKey)
                .bind("templateId", command.variantId.templateKey)
                .bind("variantId", command.variantId.key)
                .bind("templateModel", templateModelJson)
                .bind("referencedPaths", referencedPathsJson)
                .execute()

            if (updated > 0) {
                // Draft existed and was updated - return it
                return@inTransaction handle.createQuery(
                    """
                    SELECT id, tenant_key, catalog_key, variant_key, template_model, status,
                           created_at, published_at, archived_at,
                           rendering_defaults_version, resolved_theme, contract_version
                    FROM template_versions
                    WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND variant_key = :variantId
                      AND template_key = :templateId
                      AND status = 'draft'
                    """,
                )
                    .bind("tenantId", command.variantId.tenantKey)
                    .bind("catalogKey", command.variantId.catalogKey)
                    .bind("templateId", command.variantId.templateKey)
                    .bind("variantId", command.variantId.key)
                    .mapTo<TemplateVersion>()
                    .one()
            }

            // No draft exists - create new one
            // Calculate next version ID for this variant
            val nextVersionId = handle.createQuery(
                """
                SELECT COALESCE(MAX(id), 0) + 1 as next_id
                FROM template_versions
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND variant_key = :variantId
                  AND template_key = :templateId
                """,
            )
                .bind("tenantId", command.variantId.tenantKey)
                .bind("catalogKey", command.variantId.catalogKey)
                .bind("templateId", command.variantId.templateKey)
                .bind("variantId", command.variantId.key)
                .mapTo(Int::class.java)
                .one()

            // Enforce max version limit
            require(nextVersionId <= VersionKey.MAX_VERSION) {
                "Maximum version limit (${VersionKey.MAX_VERSION}) reached for variant ${command.variantId.key}"
            }

            val versionId = VersionKey.of(nextVersionId)

            // Resolve contract version (latest draft or published)
            val contractVersionId = handle.createQuery(
                """
                SELECT id FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND template_key = :templateKey
                ORDER BY CASE status WHEN 'draft' THEN 0 ELSE 1 END, id DESC
                LIMIT 1
                """,
            )
                .bind("tenantKey", command.variantId.tenantKey)
                .bind("catalogKey", command.variantId.catalogKey)
                .bind("templateKey", command.variantId.templateKey)
                .mapTo(Int::class.java)
                .findOne()
                .orElseThrow {
                    IllegalStateException("No contract version found for template '${command.variantId.templateKey}'")
                }

            handle.createQuery(
                """
                INSERT INTO template_versions (id, tenant_key, catalog_key, template_key, variant_key, template_model, status, contract_version, referenced_paths, created_at)
                VALUES (:id, :tenantId, :catalogKey, :templateId, :variantId, :templateModel::jsonb, 'draft', :contractVersion, :referencedPaths::jsonb, NOW())
                RETURNING id, tenant_key, catalog_key, variant_key, template_model, status,
                          created_at, published_at, archived_at,
                          rendering_defaults_version, resolved_theme, contract_version, referenced_paths
                """,
            )
                .bind("id", versionId)
                .bind("tenantId", command.variantId.tenantKey)
                .bind("catalogKey", command.variantId.catalogKey)
                .bind("templateId", command.variantId.templateKey)
                .bind("variantId", command.variantId.key)
                .bind("templateModel", templateModelJson)
                .bind("contractVersion", contractVersionId)
                .bind("referencedPaths", referencedPathsJson)
                .mapTo<TemplateVersion>()
                .one()
        }
    }
}
