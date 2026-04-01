package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Command to generate a single document asynchronously.
 *
 * Variant can be specified either explicitly via [variantId] or resolved automatically
 * via [variantSelectionCriteria]. Exactly one of the two must be set.
 *
 * @property tenantId Tenant that owns the template
 * @property templateId Template to use for generation
 * @property variantId Explicit variant ID (mutually exclusive with variantSelectionCriteria)
 * @property variantSelectionCriteria Attribute criteria for auto-selecting a variant (mutually exclusive with variantId)
 * @property versionId Explicit version ID (mutually exclusive with environmentId)
 * @property environmentId Environment to determine version from (mutually exclusive with versionId)
 * @property data JSON data to populate the template
 * @property filename Optional filename for the generated document
 * @property correlationId Client-provided ID for tracking documents across systems
 */
data class GenerateDocument(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    val versionId: VersionKey?,
    val environmentId: EnvironmentKey?,
    val data: ObjectNode,
    val filename: String?,
    val correlationId: String? = null,
) : Command<DocumentGenerationRequest>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId

    init {
        require(variantId == null || variantSelectionCriteria == null) {
            "Cannot specify both variantId and variantSelectionCriteria"
        }
        require((versionId != null) xor (environmentId != null)) {
            "Exactly one of versionId or environmentId must be set"
        }
    }
}

@Component
class GenerateDocumentHandler(
    private val jdbi: Jdbi,
    private val variantResolver: VariantResolver,
) : CommandHandler<GenerateDocument, DocumentGenerationRequest> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: GenerateDocument): DocumentGenerationRequest {
        // Resolve variant: explicit ID > attribute selection > default variant
        val resolvedVariantId = command.variantId
            ?: command.variantSelectionCriteria?.let { variantResolver.resolve(command.tenantId, command.templateId, it) }
            ?: resolveDefaultVariant(command.tenantId, command.templateId)

        logger.info("Generating single document for tenant {} template {} variant {}", command.tenantId, command.templateId, resolvedVariantId)

        val request = jdbi.inTransaction<DocumentGenerationRequest, Exception> { handle ->
            // 1. Verify template/variant exists and belongs to tenant
            val templateExists = handle.createQuery(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM template_variants
                    WHERE tenant_key = :tenantId AND id = :variantId AND template_key = :templateId
                )
                """,
            )
                .bind("templateId", command.templateId)
                .bind("variantId", resolvedVariantId)
                .bind("tenantId", command.tenantId)
                .mapTo<Boolean>()
                .one()

            if (!templateExists) {
                throw TemplateVariantNotFoundException(command.tenantId, command.templateId, resolvedVariantId)
            }

            // 2. Verify version or environment exists
            if (command.versionId != null) {
                val versionExists = handle.createQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM template_versions
                        WHERE tenant_key = :tenantId AND variant_key = :variantId AND id = :versionId
                    )
                    """,
                )
                    .bind("versionId", command.versionId)
                    .bind("variantId", resolvedVariantId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()

                if (!versionExists) {
                    throw VersionNotFoundException(command.tenantId, command.templateId, resolvedVariantId, command.versionId!!)
                }
            } else {
                val environmentExists = handle.createQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM environments
                        WHERE id = :environmentId
                          AND tenant_key = :tenantId
                    )
                    """,
                )
                    .bind("environmentId", command.environmentId)
                    .bind("tenantId", command.tenantId)
                    .mapTo<Boolean>()
                    .one()

                if (!environmentExists) {
                    throw EnvironmentNotFoundException(command.tenantId, command.environmentId!!)
                }
            }

            // 3. Create generation request with all data (stays in PENDING status for poller to pick up)
            val requestId = GenerationRequestKey.generate()
            val request = handle.createQuery(
                """
                INSERT INTO document_generation_requests (
                    id, batch_id, tenant_key, template_key, variant_key, version_key, environment_key,
                    data, filename, correlation_key, document_key, status
                )
                VALUES (:id, NULL, :tenantId, :templateId, :variantId, :versionId, :environmentId,
                        :data::jsonb, :filename, :correlationId, NULL, :status)
                RETURNING id, batch_id, tenant_key, template_key, variant_key, version_key, environment_key,
                          data, filename, correlation_key, document_key, status, claimed_by, claimed_at,
                          error_message, created_at, started_at, completed_at, expires_at
                """,
            )
                .bind("id", requestId)
                .bind("tenantId", command.tenantId)
                .bind("templateId", command.templateId)
                .bind("variantId", resolvedVariantId)
                .bind("versionId", command.versionId)
                .bind("environmentId", command.environmentId)
                .bind("data", command.data.toString())
                .bind("filename", command.filename)
                .bind("correlationId", command.correlationId)
                .bind("status", RequestStatus.PENDING.name)
                .mapTo<DocumentGenerationRequest>()
                .one()

            logger.info("Created generation request {} for tenant {}", request.id, command.tenantId)

            // Request stays in PENDING status - the JobPoller will pick it up
            request
        }

        return request
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
        return VariantKey.of(
            variantId ?: throw DefaultVariantNotFoundException(tenantId, templateId),
        )
    }
}
