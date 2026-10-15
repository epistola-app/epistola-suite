// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.common.NotAudited
import app.epistola.suite.common.NotEventLogged
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.NoPublishedVersionException
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.versionGenerationRemoved
import app.epistola.suite.generation.release.ReleaseTargetResolver
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.templates.templateAtAddress
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
 * @property environmentId Environment to determine version from (mutually exclusive with versionId).
 *   If neither versionId nor environmentId is provided, the latest published version is used.
 * @property data JSON data to populate the template
 * @property filename Optional filename for the generated document
 * @property correlationId Client-provided ID for tracking documents across systems
 */
data class GenerateDocument(
    val tenantId: TenantKey,
    val catalogKey: app.epistola.suite.common.ids.CatalogKey = app.epistola.suite.common.ids.CatalogKey.DEFAULT,
    val templateId: TemplateKey,
    val variantId: VariantKey? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    val versionId: VersionKey? = null,
    val environmentId: EnvironmentKey? = null,
    val data: ObjectNode,
    val filename: String?,
    val correlationId: String? = null,
    val routingKey: String? = null,
) : Command<DocumentGenerationRequest>,
    RequiresPermission,
    NotAudited,
    NotEventLogged {
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

@Component
class GenerateDocumentHandler(
    private val jdbi: Jdbi,
    private val releaseTargetResolver: ReleaseTargetResolver,
) : CommandHandler<GenerateDocument, DocumentGenerationRequest> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: GenerateDocument): DocumentGenerationRequest {
        if (command.versionId != null) throw versionGenerationRemoved()

        // Every request renders a release: the one its environment serves for the catalog, or,
        // without an environment, the catalog's latest. The variant is chosen from the variants that
        // release holds. Bound now, at acceptance: a release cut or deployed while the request waits
        // in the queue does not change what it renders.
        val target = if (command.environmentId != null) {
            releaseTargetResolver.resolveDeployed(
                command.tenantId,
                command.environmentId,
                command.catalogKey,
                command.templateId,
                command.variantId,
                command.variantSelectionCriteria,
            )
        } else {
            releaseTargetResolver.resolveLatest(
                command.tenantId,
                command.catalogKey,
                command.templateId,
                command.variantId,
                command.variantSelectionCriteria,
            )
        }

        logger.info("Generating single document for tenant {} template {} variant {} from {}@{}", command.tenantId, command.templateId, target.variantKey, command.catalogKey, target.release.version)

        return jdbi.inTransaction<DocumentGenerationRequest, Exception> { handle ->
            val requestId = GenerationRequestKey.generate()
            val request = handle.createQuery(
                """
                INSERT INTO document_generation_requests (
                    id, batch_id, tenant_key, catalog_key, template_key, variant_key, version_key, environment_key,
                    release_version, data, filename, correlation_id, routing_key, document_key, status
                )
                VALUES (:id, NULL, :tenantId, :catalogKey, :templateId, :variantId, NULL, :environmentId,
                        :releaseVersion, :data::jsonb, :filename, :correlationId, :routingKey, NULL, :status)
                RETURNING id, batch_id, tenant_key, catalog_key, template_key, variant_key, version_key, environment_key,
                          release_version, data, filename, correlation_id, routing_key, document_key, status, claimed_by,
                          claimed_at, error_message, created_at, started_at, completed_at, expires_at
                """,
            )
                .bind("id", requestId)
                .bind("tenantId", command.tenantId)
                .bind("catalogKey", command.catalogKey)
                .bind("templateId", command.templateId)
                .bind("variantId", target.variantKey)
                .bind("environmentId", command.environmentId)
                .bind("releaseVersion", target.release.version)
                .bind("data", command.data.toString())
                .bind("filename", command.filename)
                .bind("correlationId", command.correlationId)
                .bind("routingKey", command.routingKey)
                .bind("status", RequestStatus.PENDING.name)
                .mapTo<DocumentGenerationRequest>()
                .one()

            logger.info("Created generation request {} for tenant {}", request.id, command.tenantId)
            request
        }
    }
}
