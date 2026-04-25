package app.epistola.suite.templates.commands.contracts

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.ContractVersion
import app.epistola.suite.templates.model.ContractVersionStatus
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.SchemaCompatibilityChecker
import app.epistola.suite.templates.validation.SchemaValidationResult
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Publishes the draft contract version for a template.
 *
 * Flow:
 * 1. Validates schema and examples
 * 2. Checks backwards compatibility against previous published version
 * 3. Publishes the draft (status = 'published')
 * 4. Auto-upgrades template versions:
 *    - Compatible: all versions (draft, published, archived) from N-1 → N
 *    - Breaking: only draft versions from N-1 → N
 */
data class PublishContractVersion(
    val templateId: TemplateId,
) : Command<PublishContractVersionResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class PublishContractVersionResult(
    val publishedVersion: ContractVersion,
    val compatible: Boolean,
    val breakingChanges: List<SchemaCompatibilityChecker.BreakingChange>,
    val upgradedVersionCount: Int,
)

@Component
class PublishContractVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
    private val compatibilityChecker: SchemaCompatibilityChecker,
) : CommandHandler<PublishContractVersion, PublishContractVersionResult?> {
    override fun handle(command: PublishContractVersion): PublishContractVersionResult? {
        requireCatalogEditable(command.templateId.tenantKey, command.templateId.catalogKey)
        return jdbi.inTransaction<PublishContractVersionResult?, Exception> { handle ->
            // 1. Load draft contract version
            val draft = handle.createQuery(
                """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'draft'
                FOR UPDATE
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .mapTo<ContractVersion>()
                .findOne()
                .orElse(null) ?: return@inTransaction null

            // 2. Validate schema and examples
            if (draft.dataModel != null) {
                val schemaValidation = jsonSchemaValidator.validateSchema(objectMapper.writeValueAsString(draft.dataModel))
                require(schemaValidation is SchemaValidationResult.Valid) {
                    "Invalid JSON Schema: ${(schemaValidation as SchemaValidationResult.Invalid).message}"
                }

                val invalidNames = jsonSchemaValidator.validatePropertyNames(draft.dataModel)
                require(invalidNames.isEmpty()) { "Invalid property names: ${invalidNames.joinToString()}" }

                if (draft.dataExamples.isNotEmpty()) {
                    val exampleErrors = jsonSchemaValidator.validateExamples(draft.dataModel, draft.dataExamples.toList())
                    require(exampleErrors.isEmpty()) {
                        "Examples are invalid against the schema: ${exampleErrors.entries.joinToString { "${it.key}: ${it.value.joinToString { e -> e.message }}" }}"
                    }
                }
            }

            // 3. Check backwards compatibility against previous published version
            val previousPublished = handle.createQuery(
                """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = 'published'
                ORDER BY id DESC LIMIT 1
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .mapTo<ContractVersion>()
                .findOne()
                .orElse(null)

            val compatibilityResult = if (previousPublished != null) {
                compatibilityChecker.checkCompatibility(previousPublished.dataModel, draft.dataModel)
            } else {
                SchemaCompatibilityChecker.CompatibilityResult(compatible = true, breakingChanges = emptyList())
            }

            // 4. Publish the draft
            handle.createUpdate(
                """
                UPDATE contract_versions
                SET status = 'published', published_at = NOW()
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND id = :versionId
                """,
            )
                .bind("tenantKey", command.templateId.tenantKey)
                .bind("catalogKey", command.templateId.catalogKey)
                .bind("templateKey", command.templateId.key)
                .bind("versionId", draft.id)
                .execute()

            // 5. Auto-upgrade template versions from previous contract version
            var upgradedCount = 0
            if (previousPublished != null) {
                val upgradeQuery = if (compatibilityResult.compatible) {
                    // Compatible: upgrade ALL versions (draft, published, archived)
                    """
                    UPDATE template_versions
                    SET contract_version = :newVersion
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                      AND template_key = :templateKey AND contract_version = :oldVersion
                    """
                } else {
                    // Breaking: upgrade only draft versions
                    """
                    UPDATE template_versions
                    SET contract_version = :newVersion
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                      AND template_key = :templateKey AND contract_version = :oldVersion
                      AND status = 'draft'
                    """
                }

                upgradedCount = handle.createUpdate(upgradeQuery)
                    .bind("tenantKey", command.templateId.tenantKey)
                    .bind("catalogKey", command.templateId.catalogKey)
                    .bind("templateKey", command.templateId.key)
                    .bind("newVersion", draft.id.value)
                    .bind("oldVersion", previousPublished.id.value)
                    .execute()
            }

            val publishedVersion = draft.copy(
                status = ContractVersionStatus.PUBLISHED,
            )

            PublishContractVersionResult(
                publishedVersion = publishedVersion,
                compatible = compatibilityResult.compatible,
                breakingChanges = compatibilityResult.breakingChanges,
                upgradedVersionCount = upgradedCount,
            )
        }
    }
}
