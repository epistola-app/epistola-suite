// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.catalog.bindCatalog
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
import app.epistola.suite.documents.DefaultVariantNotFoundException
import app.epistola.suite.documents.DocumentVisibility
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.NoPublishedVersionException
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.documents.interactive.BoundDataContract
import app.epistola.suite.documents.interactive.InteractiveFormLinks
import app.epistola.suite.documents.interactive.InteractiveGeneration
import app.epistola.suite.documents.interactive.InteractiveGenerationProperties
import app.epistola.suite.documents.interactive.InteractiveState
import app.epistola.suite.documents.interactive.InteractiveTrigger
import app.epistola.suite.documents.interactive.ReturnOriginPolicy
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.environments.DeploymentUsage
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.generation.release.GenerationTarget
import app.epistola.suite.generation.release.ReleaseTargetResolver
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.services.VariantResolver
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.templates.validation.MissingDataField
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Command to generate a single document asynchronously.
 *
 * Variant can be specified either explicitly via [variantId] or resolved automatically
 * via [variantSelectionCriteria]. Exactly one of the two must be set.
 *
 * @property catalog The template's catalog, in the tenant that owns it
 * @property templateId Template to use for generation
 * @property variantId Explicit variant ID (mutually exclusive with variantSelectionCriteria)
 * @property variantSelectionCriteria Attribute criteria for auto-selecting a variant (mutually exclusive with variantId)
 * @property releaseVersion A version selector (npm range grammar, an exact version included): the
 *   highest candidate release satisfying it is rendered. Despite the name it need not be a release.
 * @property environmentId Environment whose releases are the candidates; without it, every release
 *   of the catalog that kept its content is. With neither, the highest candidate is rendered.
 * @property data JSON data to populate the template
 * @property filename Optional filename for the generated document
 * @property correlationId Client-provided ID for tracking documents across systems
 * @property interactive Ask a person to complete the data in a form first (contract ADR 0011,
 *   alpha). Ignored, as a server that predates it would, while the tenant's
 *   `interactive-generation` toggle is off or the installation has no public base URL; the result's
 *   [DocumentGenerationRequest.interactive] is then null, which is how a caller tells.
 */
data class GenerateDocument(
    /** The template's catalog, by its address (ADR 0034, section 9). */
    val catalog: CatalogId,
    val templateId: TemplateKey,
    val variantId: VariantKey? = null,
    val variantSelectionCriteria: VariantSelectionCriteria? = null,
    val releaseVersion: String? = null,
    val environmentId: EnvironmentKey? = null,
    val data: ObjectNode,
    val filename: String?,
    val correlationId: String? = null,
    val routingKey: String? = null,
    val interactive: InteractiveGeneration? = null,
) : Command<DocumentGenerationRequest>,
    RequiresPermission,
    NotAudited,
    NotEventLogged {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = catalog.tenantKey
    val tenantId: TenantKey get() = catalog.tenantKey
    val catalogKey: app.epistola.suite.common.ids.CatalogKey get() = catalog.key

    init {
        require(variantId == null || variantSelectionCriteria == null) {
            "Cannot specify both variantId and variantSelectionCriteria"
        }
    }
}

@Component
class GenerateDocumentHandler(
    private val jdbi: Jdbi,
    private val releaseTargetResolver: ReleaseTargetResolver,
    private val usage: DeploymentUsage,
    private val mediator: Mediator,
    private val boundContract: BoundDataContract,
    private val interactiveProperties: InteractiveGenerationProperties,
    private val originPolicy: ReturnOriginPolicy,
    private val formLinks: InteractiveFormLinks,
    private val objectMapper: ObjectMapper,
) : CommandHandler<GenerateDocument, DocumentGenerationRequest> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: GenerateDocument): DocumentGenerationRequest {
        // Every request renders a release: the one it names, the one its environment serves for the
        // catalog (or its override names), or the catalog's latest; or the draft its environment's
        // override follows, live. The variant is chosen from the variants that
        // release holds. Bound now, at acceptance: a release cut or deployed while the request waits
        // in the queue does not change what it renders.
        val target = releaseTargetResolver.resolveForGeneration(
            command.catalog,
            command.templateId,
            command.variantId,
            command.variantSelectionCriteria,
            command.environmentId,
            command.releaseVersion,
        )

        log.info("Generating single document tenant={} template={} variant={} catalog={} version={}", command.tenantId, command.templateId, target.variantKey, command.catalogKey, target.releaseVersion ?: target.draft?.label)

        val interactive = command.interactive?.takeIf { interactiveAvailable(command) }
        val form = interactive?.let { planForm(command, target, it) }
        val application = DocumentVisibility.submittingApplication()

        return jdbi.inTransaction<DocumentGenerationRequest, Exception> { handle ->
            if (interactive != null) originPolicy.check(handle, command.tenantKey, application, interactive)
            val requestId = GenerationRequestKey.generate()
            val request = handle.createQuery(
                """
                INSERT INTO document_generation_requests (
                    id, batch_id, tenant_key, catalog_key, registry, namespace, template_key, variant_key, version_key,
                    environment_key, release_version, draft_id, draft_label, catalog_version_id, template_resource_id, data, filename, correlation_id, routing_key, document_key, status,
                    application_id, has_form
                )
                VALUES (:id, NULL, :tenantId, :catalogKey,
                        (SELECT registry FROM catalogs WHERE tenant_key = :tenantId AND catalog_id = epistola_catalog_id(:tenantId, :catalogKey_ns, :catalogKey)),
                        (SELECT namespace FROM catalogs WHERE tenant_key = :tenantId AND catalog_id = epistola_catalog_id(:tenantId, :catalogKey_ns, :catalogKey)),
                        :templateId, :variantId, NULL, :environmentId,
                        :releaseVersion, :draftId, :draftLabel,
                        COALESCE(:draftId, (SELECT r.version_key FROM catalog_versions r JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
                         WHERE r.state = 'released' AND c.tenant_key = :tenantId AND c.catalog_id = epistola_catalog_id(:tenantId, :catalogKey_ns, :catalogKey) AND r.version = :releaseVersion)),
                        :templateResourceId, :data::jsonb, :filename, :correlationId, :routingKey, NULL, :status,
                        :applicationId, :hasForm)
                RETURNING id, batch_id, tenant_key, catalog_key, registry, namespace, template_key, variant_key, version_key, environment_key,
                          release_version, draft_id, draft_label, catalog_version_id, data, filename, correlation_id, routing_key, document_key, status, claimed_by,
                          claimed_at, error_message, created_at, started_at, completed_at, expires_at, has_form
                """,
            )
                .bind("id", requestId)
                .bind("tenantId", command.tenantId)
                .bindCatalog("catalogKey", command.catalog)
                .bind("templateId", command.templateId)
                .bind("variantId", target.variantKey)
                .bind("environmentId", command.environmentId)
                .bind("releaseVersion", target.releaseVersion)
                .bind("draftId", target.draft?.draftId)
                .bind("draftLabel", target.draft?.label)
                .bind("templateResourceId", target.templateResourceId)
                .bind("data", command.data.toString())
                .bind("filename", command.filename)
                .bind("correlationId", command.correlationId)
                .bind("routingKey", command.routingKey)
                .bind("status", (if (form?.asks == true) RequestStatus.AWAITING_INPUT else RequestStatus.PENDING).name)
                // From the credential, never the request body (ADR 0054).
                .bind("applicationId", application?.value)
                .bind("hasForm", form != null)
                .mapTo<DocumentGenerationRequest>()
                .one()

            val accepted = if (interactive != null && form != null) {
                request.copy(interactive = insertForm(handle, request, interactive, form, command.data))
            } else {
                request
            }
            log.info("Created generation request request={} tenant={} status={}", request.id, command.tenantId, accepted.status)
            accepted
        }.also {
            // How much the deployed version is used, for deciding when to undeploy it.
            val version = target.releaseVersion
            if (command.environmentId != null && version != null) usage.record(command.environmentId, command.catalog, version)
        }
    }

    /** Honoured only when the installation can serve forms and the tenant has the feature on. */
    private fun interactiveAvailable(command: GenerateDocument): Boolean = interactiveProperties.configured &&
        mediator.query(ResolveFeatureToggles(command.tenantKey))[KnownFeatures.INTERACTIVE_GENERATION] == true

    /** What the form will be: whether it asks, what for, and until when. Refuses an expiry above the maximum. */
    private fun planForm(command: GenerateDocument, target: GenerationTarget, interactive: InteractiveGeneration): PlannedForm {
        val maxMinutes = interactiveProperties.maxExpiry.toMinutes()
        val minutes = interactive.expiresInMinutes?.toLong() ?: interactiveProperties.defaultExpiry.toMinutes()
        validate("interactive.expiresInMinutes", minutes <= maxMinutes, ValidationCode.MAXIMUM) { "at most $maxMinutes minutes" }

        // The analysis validateTemplateData runs, against the binding just made and the data exactly
        // as sent: never another version, never a data example in place of empty data.
        val contract = boundContract.of(command.catalog, command.templateId, target.variantKey, target.releaseVersion, target.draft?.draftId, command.environmentId)
        val analysis = boundContract.analyze(contract, command.data)
        val asks = interactive.trigger == InteractiveTrigger.ALWAYS || !analysis.valid
        return PlannedForm(asks, if (asks) analysis.missingFields else emptyList(), minutes)
    }

    private fun insertForm(
        handle: Handle,
        request: DocumentGenerationRequest,
        interactive: InteractiveGeneration,
        form: PlannedForm,
        callerData: ObjectNode,
    ): InteractiveState {
        val expiresAt = if (form.asks) EpistolaClock.offsetDateTime().plusMinutes(form.expiresInMinutes) else null
        handle.createUpdate(
            """
            INSERT INTO generation_request_interactions (
                request_id, created_at, tenant_key, trigger, expires_at, return_url, title, instructions,
                links, locale, missing_fields, caller_data, edited_fields
            )
            VALUES (
                :requestId, :createdAt, :tenantKey, :trigger, :expiresAt, :returnUrl, :title, :instructions,
                :links::jsonb, :locale, :missingFields::jsonb, :callerData::jsonb, :editedFields::jsonb
            )
            """,
        )
            .bind("requestId", request.id)
            .bind("createdAt", request.createdAt)
            .bind("tenantKey", request.tenantKey)
            .bind("trigger", interactive.trigger.name)
            .bind("expiresAt", expiresAt)
            .bind("returnUrl", interactive.returnUrl)
            .bind("title", interactive.title)
            .bind("instructions", interactive.instructions)
            .bind("links", objectMapper.writeValueAsString(interactive.links))
            .bind("locale", interactive.locale)
            .bind("missingFields", objectMapper.writeValueAsString(form.missingFields))
            .bind("callerData", callerData.toString())
            // Nothing asked: the caller's data is final as sent, with nothing edited.
            .bind("editedFields", if (form.asks) null else "[]")
            .execute()
        val link = expiresAt?.let { formLinks.issue(request.id, it) }
        return InteractiveState(
            trigger = interactive.trigger,
            url = link?.url,
            expiresAt = expiresAt,
            missingFields = form.missingFields,
            editedFields = if (form.asks) null else emptyList(),
            urlExpiresAt = link?.expiresAt,
        )
    }

    private data class PlannedForm(val asks: Boolean, val missingFields: List<MissingDataField>, val expiresInMinutes: Long)
}
