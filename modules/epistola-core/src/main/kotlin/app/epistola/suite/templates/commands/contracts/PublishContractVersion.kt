package app.epistola.suite.templates.commands.contracts

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.ContractVersion
import app.epistola.suite.templates.model.ContractVersionStatus
import app.epistola.suite.templates.queries.contracts.CheckContractPublishImpact
import app.epistola.suite.templates.queries.contracts.ContractPublishImpact
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
 * For breaking changes, requires `confirmed = true`. When `confirmed = false`
 * and breaking changes are detected, returns a preview of the impact without
 * actually publishing.
 *
 * Flow:
 * 1. Validates schema and examples
 * 2. Checks backwards compatibility against previous published version
 * 3. If breaking and not confirmed: return preview (no publish)
 * 4. Publishes the draft (status = 'published')
 * 5. Auto-upgrades template versions:
 *    - Compatible: all versions (draft, published, archived) from N-1 → N
 *    - Breaking (confirmed): only draft versions from N-1 → N
 */
data class PublishContractVersion(
    val templateId: TemplateId,
    val confirmed: Boolean = false,
) : Command<PublishContractVersionResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class PublishContractVersionResult(
    val published: Boolean,
    val publishedVersion: ContractVersion?,
    val compatible: Boolean,
    val breakingChanges: List<SchemaCompatibilityChecker.BreakingChange>,
    val upgradedVersionCount: Int,
    val incompatibleVersions: List<IncompatibleVersion>,
)

data class IncompatibleVersion(
    val variantKey: VariantKey,
    val versionId: VersionKey,
    val activeEnvironments: List<String>,
    val incompatibilities: List<app.epistola.suite.templates.analysis.FieldIncompatibility> = emptyList(),
)

@Component
class PublishContractVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
    private val mediator: Mediator,
) : CommandHandler<PublishContractVersion, PublishContractVersionResult?> {

    private val compatibilityChecker = SchemaCompatibilityChecker()

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

            // 4. If breaking: get impact analysis via query
            val impact: ContractPublishImpact? = if (!compatibilityResult.compatible) {
                mediator.query(CheckContractPublishImpact(templateId = command.templateId))
            } else {
                null
            }

            // 5. If breaking and not confirmed: return preview without publishing
            if (!compatibilityResult.compatible && !command.confirmed) {
                return@inTransaction PublishContractVersionResult(
                    published = false,
                    publishedVersion = null,
                    compatible = false,
                    breakingChanges = compatibilityResult.breakingChanges,
                    upgradedVersionCount = 0,
                    incompatibleVersions = impact?.incompatibleVersions ?: emptyList(),
                )
            }

            val incompatibleVersions = impact?.incompatibleVersions ?: emptyList()

            // 6. Publish the draft
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

            // 7. Auto-upgrade template versions from previous contract version
            // Compatible: upgrade all. Breaking: upgrade all except truly incompatible.
            var upgradedCount = 0
            if (previousPublished != null) {
                if (compatibilityResult.compatible || incompatibleVersions.isEmpty()) {
                    // Upgrade all versions on the old contract
                    upgradedCount = handle.createUpdate(
                        """
                        UPDATE template_versions
                        SET contract_version = :newVersion
                        WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                          AND template_key = :templateKey AND contract_version = :oldVersion
                        """,
                    )
                        .bind("tenantKey", command.templateId.tenantKey)
                        .bind("catalogKey", command.templateId.catalogKey)
                        .bind("templateKey", command.templateId.key)
                        .bind("newVersion", draft.id.value)
                        .bind("oldVersion", previousPublished.id.value)
                        .execute()
                } else {
                    // Breaking with some incompatible: upgrade all EXCEPT the incompatible ones
                    val incompatibleIds = incompatibleVersions.map { it.versionId.value }
                    upgradedCount = handle.createUpdate(
                        """
                        UPDATE template_versions
                        SET contract_version = :newVersion
                        WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                          AND template_key = :templateKey AND contract_version = :oldVersion
                          AND id NOT IN (<incompatibleIds>)
                        """,
                    )
                        .bind("tenantKey", command.templateId.tenantKey)
                        .bind("catalogKey", command.templateId.catalogKey)
                        .bind("templateKey", command.templateId.key)
                        .bind("newVersion", draft.id.value)
                        .bind("oldVersion", previousPublished.id.value)
                        .bindList("incompatibleIds", incompatibleIds)
                        .execute()
                }
            }

            PublishContractVersionResult(
                published = true,
                publishedVersion = draft.copy(status = ContractVersionStatus.PUBLISHED),
                compatible = compatibilityResult.compatible,
                breakingChanges = compatibilityResult.breakingChanges,
                upgradedVersionCount = upgradedCount,
                incompatibleVersions = incompatibleVersions,
            )
        }
    }
}
