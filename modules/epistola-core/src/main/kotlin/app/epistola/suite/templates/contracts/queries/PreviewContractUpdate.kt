package app.epistola.suite.templates.contracts.queries

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.contracts.SchemaCompatibilityChecker
import app.epistola.suite.templates.contracts.model.ContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.ValidationError
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Previews — without persisting anything — the outcome of applying [dataModel] /
 * [dataExamples] to a template's contract and publishing it in one shot (the REST
 * "authoritative" update flow).
 *
 * It reproduces exactly what `CreateContractVersion -> UpdateContractVersion ->
 * PublishContractVersion` would compute, by using the same baseline those commands
 * use: the existing draft when one exists, otherwise the latest published contract.
 * Examples are validated against the effective data model, and the effective data
 * model is checked for backwards-compatibility against the published contract.
 *
 * Callers run this first so they can reject incompatible examples (422) or an
 * unconfirmed breaking schema change (409) *before* any draft is created — those
 * three commands each commit in their own transaction, so deciding after the fact
 * would leave a dangling draft behind on rejection.
 */
data class PreviewContractUpdate(
    val templateId: TemplateId,
    val dataModel: ObjectNode?,
    val dataExamples: List<DataExample>?,
) : Query<ContractUpdatePreview>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

data class ContractUpdatePreview(
    /** Map of example name to its validation errors; empty when every example is valid. */
    val exampleValidationErrors: Map<String, List<ValidationError>>,
    /** False when the effective data model is a breaking change over the published contract. */
    val compatible: Boolean,
    /** Human-readable descriptions of the detected breaking changes (empty when compatible). */
    val breakingChanges: List<String>,
)

@Component
class PreviewContractUpdateHandler(
    private val jdbi: Jdbi,
    private val jsonSchemaValidator: JsonSchemaValidator,
) : QueryHandler<PreviewContractUpdate, ContractUpdatePreview> {

    private val compatibilityChecker = SchemaCompatibilityChecker()

    override fun handle(query: PreviewContractUpdate): ContractUpdatePreview {
        val draft = loadContract(query.templateId, status = "draft")
        val published = loadContract(query.templateId, status = "published")

        // The create -> update -> publish flow edits the existing draft when present
        // (else a fresh copy of the latest published contract), so the effective values
        // fall back to that same baseline.
        val base = draft ?: published
        val effectiveDataModel = query.dataModel ?: base?.dataModel
        val effectiveExamples = query.dataExamples ?: base?.dataExamples?.toList() ?: emptyList()

        val exampleValidationErrors = if (effectiveDataModel != null && effectiveExamples.isNotEmpty()) {
            jsonSchemaValidator.validateExamples(effectiveDataModel, effectiveExamples)
        } else {
            emptyMap()
        }

        // Publishing compares the (effective) draft model against the latest published one.
        val compatibility = if (published != null) {
            compatibilityChecker.checkCompatibility(published.dataModel, effectiveDataModel)
        } else {
            SchemaCompatibilityChecker.CompatibilityResult(compatible = true, breakingChanges = emptyList())
        }

        return ContractUpdatePreview(
            exampleValidationErrors = exampleValidationErrors,
            compatible = compatibility.compatible,
            breakingChanges = compatibility.breakingChanges.map { it.description },
        )
    }

    private fun loadContract(
        templateId: TemplateId,
        status: String,
    ): ContractVersion? = jdbi.withHandle<ContractVersion?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                   status, created_at, published_at, created_by
            FROM contract_versions
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
              AND template_key = :templateKey AND status = :status
            ORDER BY id DESC LIMIT 1
            """,
        )
            .bind("tenantKey", templateId.tenantKey)
            .bind("catalogKey", templateId.catalogKey)
            .bind("templateKey", templateId.key)
            .bind("status", status)
            .mapTo<ContractVersion>()
            .findOne()
            .orElse(null)
    }
}
