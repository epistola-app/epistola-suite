package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.generation.DocumentPreviewRenderer
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.activations.GetActiveVersion
import app.epistola.suite.templates.queries.versions.GetLatestPublishedVersion
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.tenants.queries.GetTenant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.json.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Preview a published version via the REST API.
 *
 * @property tenantId Tenant that owns the template
 * @property templateId Template to preview
 * @property data JSON data to populate the template
 * @property variantId Explicit variant (mutually exclusive with variantSelectionCriteria)
 * @property variantSelectionCriteria Attribute criteria for auto-selecting a variant
 * @property versionId Explicit version (mutually exclusive with environmentId)
 * @property environmentId Environment to determine version from (mutually exclusive with versionId)
 */
data class PreviewDocument(
    val tenantId: TenantKey,
    val catalogKey: app.epistola.suite.common.ids.CatalogKey,
    val templateId: TemplateKey,
    val data: ObjectNode,
    val variantId: VariantKey? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    val versionId: VersionKey? = null,
    val environmentId: EnvironmentKey? = null,
) : Query<ByteArray>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId

    init {
        require(variantId == null || variantSelectionCriteria == null) {
            "Cannot specify both variantId and variantSelectionCriteria"
        }
        require(!(versionId != null && environmentId != null)) {
            "Cannot specify both versionId and environmentId"
        }
    }
}

private data class ContractDataModelRow(
    @Json val dataModel: ObjectNode? = null,
)

@Component
class PreviewDocumentHandler(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
    private val schemaValidator: JsonSchemaValidator,
    private val variantResolver: VariantResolver,
    private val renderer: DocumentPreviewRenderer,
) : QueryHandler<PreviewDocument, ByteArray> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(query: PreviewDocument): ByteArray {
        val tenantId = TenantId(query.tenantId)
        val catalogId = CatalogId(query.catalogKey, tenantId)
        val templateId = TemplateId(query.templateId, catalogId)

        // 1. Resolve variant
        val resolvedVariantKey = query.variantId
            ?: query.variantSelectionCriteria?.let { variantResolver.resolve(query.tenantId, query.templateId, it) }
            ?: resolveDefaultVariant(query.tenantId, query.catalogKey, query.templateId)

        val variantId = VariantId(resolvedVariantKey, templateId)

        logger.debug(
            "Preview for tenant={} template={} variant={} version={} env={}",
            query.tenantId,
            query.templateId,
            resolvedVariantKey,
            query.versionId,
            query.environmentId,
        )

        // 2. Resolve version
        val version = if (query.versionId != null) {
            val vid = VersionId(query.versionId, variantId)
            mediator.query(GetVersion(vid))
                ?: throw IllegalStateException("Version ${query.versionId} not found")
        } else if (query.environmentId != null) {
            val envId = EnvironmentId(query.environmentId, tenantId)
            mediator.query(GetActiveVersion(variantId, envId))
                ?: throw IllegalStateException("No active version for environment ${query.environmentId}")
        } else {
            // Fallback: latest published version
            mediator.query(GetLatestPublishedVersion(variantId))
                ?: throw IllegalStateException("No published version found for variant $resolvedVariantKey")
        }

        // 3. Fetch template and tenant for theme resolution
        val template = mediator.query(GetDocumentTemplate(templateId))
            ?: throw IllegalStateException("Template ${query.templateId} not found")
        val tenant = mediator.query(GetTenant(id = query.tenantId))
            ?: throw IllegalStateException("Tenant ${query.tenantId} not found")

        // 4. Validate data against contract schema
        val dataModel: ObjectNode? = version.contractVersion?.let { cv ->
            val contractVersion = mediator.query(
                app.epistola.suite.templates.queries.contracts.GetContractVersion(
                    id = app.epistola.suite.common.ids.ContractVersionId(cv, TemplateId(query.templateId, CatalogId(query.catalogKey, tenantId))),
                ),
            )
            contractVersion?.dataModel
        }
        if (dataModel != null) {
            val errors = schemaValidator.validate(dataModel, query.data)
            if (errors.isNotEmpty()) {
                val errorMessages = errors.joinToString("; ") { "${it.path}: ${it.message}" }
                throw IllegalArgumentException("Data validation failed: $errorMessages")
            }
        }

        // 5. Render
        return renderer.render(
            tenantId = query.tenantId,
            templateModel = version.templateModel,
            version = version,
            template = template,
            tenant = tenant,
            data = query.data,
        )
    }

    private fun resolveDefaultVariant(tenantId: TenantKey, catalogKey: app.epistola.suite.common.ids.CatalogKey, templateId: TemplateKey): VariantKey {
        val variantId = jdbi.withHandle<String?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id FROM template_variants
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND template_key = :templateId AND is_default = TRUE
                """,
            )
                .bind("tenantId", tenantId)
                .bind("catalogKey", catalogKey)
                .bind("templateId", templateId)
                .mapTo<String>()
                .findOne()
                .orElse(null)
        }
        requireNotNull(variantId) {
            "No default variant found for template $templateId in tenant $tenantId"
        }
        return VariantKey.of(variantId)
    }
}
