package app.epistola.suite.documents.queries

import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.generation.pdf.PdfMetadata
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.common.ids.AssetKey
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
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.activations.GetActiveVersion
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.json.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream

/**
 * Unified preview query supporting both API preview (published versions)
 * and editor preview (drafts / live template model).
 *
 * Template model resolution priority:
 * 1. [templateModel] — live editor override (highest priority)
 * 2. [versionId] or [environmentId] — fetch published version
 * 3. Neither — fetch draft version
 *
 * @property tenantId Tenant that owns the template
 * @property templateId Template to preview
 * @property variantId Explicit variant (mutually exclusive with variantSelectionCriteria)
 * @property variantSelectionCriteria Attribute criteria for auto-selecting a variant
 * @property data JSON data to populate the template
 * @property versionId Explicit version (mutually exclusive with environmentId)
 * @property environmentId Environment to determine version from (mutually exclusive with versionId)
 * @property templateModel Optional live template model override (for editor preview)
 */
data class PreviewDocument(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    val data: ObjectNode,
    val versionId: VersionKey? = null,
    val environmentId: EnvironmentKey? = null,
    val templateModel: TemplateDocument? = null,
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

private data class DraftRow(
    @Json val draftTemplateModel: TemplateDocument?,
)

@Component
class PreviewDocumentHandler(
    private val jdbi: Jdbi,
    private val mediator: Mediator,
    private val generationService: GenerationService,
    private val objectMapper: ObjectMapper,
    private val schemaValidator: JsonSchemaValidator,
    private val variantResolver: VariantResolver,
) : QueryHandler<PreviewDocument, ByteArray> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(query: PreviewDocument): ByteArray {
        val tenantId = TenantId(query.tenantId)
        val templateId = TemplateId(query.templateId, tenantId)

        // 1. Resolve variant
        val resolvedVariantKey = query.variantId
            ?: query.variantSelectionCriteria?.let { variantResolver.resolve(query.tenantId, query.templateId, it) }
            ?: resolveDefaultVariant(query.tenantId, query.templateId)

        val variantId = VariantId(resolvedVariantKey, templateId)

        logger.debug(
            "Preview for tenant={} template={} variant={} version={} env={} liveModel={}",
            query.tenantId,
            query.templateId,
            resolvedVariantKey,
            query.versionId,
            query.environmentId,
            query.templateModel != null,
        )

        // 2. Resolve template model
        val version = when {
            query.templateModel != null -> null // Live model from editor — no version needed
            query.versionId != null -> {
                val vid = VersionId(query.versionId, variantId)
                mediator.query(GetVersion(vid))
                    ?: throw IllegalStateException("Version ${query.versionId} not found")
            }
            query.environmentId != null -> {
                val envId = EnvironmentId(query.environmentId, tenantId)
                mediator.query(GetActiveVersion(variantId, envId))
                    ?: throw IllegalStateException("No active version for environment ${query.environmentId}")
            }
            else -> null // Draft mode
        }

        val templateModel = when {
            query.templateModel != null -> query.templateModel
            version != null -> version.templateModel
            else -> fetchDraft(query.tenantId, query.templateId, resolvedVariantKey)
                ?: throw IllegalStateException("No draft found for variant $resolvedVariantKey")
        }

        // 3. Fetch template and tenant for theme resolution
        val template = mediator.query(GetDocumentTemplate(templateId))
            ?: throw IllegalStateException("Template ${query.templateId} not found")
        val tenant = mediator.query(GetTenant(id = query.tenantId))
            ?: throw IllegalStateException("Tenant ${query.tenantId} not found")

        // 4. Validate data against schema
        if (template.dataModel != null) {
            val errors = schemaValidator.validate(template.dataModel, query.data)
            if (errors.isNotEmpty()) {
                val errorMessages = errors.joinToString("; ") { "${it.path}: ${it.message}" }
                throw IllegalArgumentException("Data validation failed: $errorMessages")
            }
        }

        // 5. Render PDF
        val outputStream = ByteArrayOutputStream()

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(query.data, Map::class.java) as Map<String, Any?>
        val metadata = PdfMetadata(
            title = template.name,
            author = tenant.name,
        )
        val assetResolver = AssetResolver { assetId ->
            mediator.query(GetAssetContent(query.tenantId, AssetKey.of(assetId)))
                ?.let { AssetResolution(it.content, it.mediaType.mimeType) }
        }

        // Use snapshot rendering for published versions that have it, live cascade otherwise
        val resolvedTheme = version?.resolvedTheme
        val renderingDefaultsVersion = version?.renderingDefaultsVersion
        if (resolvedTheme != null && renderingDefaultsVersion != null) {
            val renderingDefaults = RenderingDefaults.forVersion(renderingDefaultsVersion)
            val metadataWithEngine = metadata.copy(engineVersion = renderingDefaults.engineVersionString())
            generationService.renderPdfWithSnapshot(
                templateModel,
                dataMap,
                outputStream,
                resolvedTheme,
                renderingDefaults,
                metadataWithEngine,
                pdfaCompliant = false,
                assetResolver = assetResolver,
            )
        } else {
            val metadataWithEngine = metadata.copy(
                engineVersion = RenderingDefaults.CURRENT.engineVersionString(),
            )
            generationService.renderPdf(
                query.tenantId,
                templateModel,
                dataMap,
                outputStream,
                template.themeKey,
                tenant.defaultThemeKey,
                metadataWithEngine,
                pdfaCompliant = false,
                assetResolver = assetResolver,
            )
        }

        return outputStream.toByteArray()
    }

    private fun resolveDefaultVariant(tenantId: TenantKey, templateId: TemplateKey): VariantKey {
        val variantId = jdbi.withHandle<String?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id FROM template_variants
                WHERE tenant_key = :tenantId AND template_key = :templateId AND is_default = TRUE
                """,
            )
                .bind("tenantId", tenantId)
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

    private fun fetchDraft(tenantId: TenantKey, templateId: TemplateKey, variantKey: VariantKey): TemplateDocument? = jdbi.withHandle<TemplateDocument?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT ver.template_model as draft_template_model
                FROM template_versions ver
                WHERE ver.tenant_key = :tenantId
                  AND ver.template_key = :templateId
                  AND ver.variant_key = :variantId
                  AND ver.status = 'draft'
                """,
        )
            .bind("tenantId", tenantId)
            .bind("templateId", templateId)
            .bind("variantId", variantKey)
            .mapTo<DraftRow>()
            .findOne()
            .map { it.draftTemplateModel }
            .orElse(null)
    }
}
