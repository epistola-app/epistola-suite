// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.generation.pdf.PdfMetadata
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.generation.DocumentPreviewRenderer
import app.epistola.suite.generation.DraftDocumentRenderer
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.generation.generationContext
import app.epistola.suite.generation.release.ReleaseRef
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.tenants.queries.GetTenant
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renders the preview on a form (contract ADR 0011: "the renderer, so the form can preview"): the
 * request's own target, its bound release or the draft its environment follows, its variant, with
 * the data on the form and the preview watermark, exactly as a worker would render it after
 * submission. Nothing in the call chooses a template, release or variant.
 *
 * It is the renderer on a page reachable without signing in, so each form is limited, per node:
 * one preview at a time, and [PREVIEWS_PER_MINUTE] a minute. The counters are held in bounded
 * caches, so forms nobody previews any more cost nothing.
 */
@Component
class InteractiveFormPreview(
    private val releaseRenderSource: ReleaseRenderSource,
    private val generationService: GenerationService,
    private val draftRenderer: DraftDocumentRenderer,
    private val schemaValidator: JsonSchemaValidator,
    private val localeResolver: TenantLocaleResolver,
    private val objectMapper: ObjectMapper,
    private val mediator: Mediator,
) {
    private val inFlight = Caffeine.newBuilder().maximumSize(MAX_TRACKED_FORMS).expireAfterAccess(Duration.ofMinutes(10))
        .build<GenerationRequestKey, Semaphore>()
    private val perMinute = Caffeine.newBuilder().maximumSize(MAX_TRACKED_FORMS).expireAfterWrite(Duration.ofMinutes(1))
        .build<GenerationRequestKey, AtomicInteger>()

    /** The PDF, or null when this form is over its limits. */
    fun render(request: DocumentGenerationRequest, data: ObjectNode): ByteArray? {
        if (perMinute.get(request.id) { AtomicInteger() }.incrementAndGet() > PREVIEWS_PER_MINUTE) return null
        val gate = inFlight.get(request.id) { Semaphore(1) }
        if (!gate.tryAcquire()) return null
        try {
            return SecurityContext.runWithPrincipal(SystemUser.principalForTenant(request.tenantKey)) { renderNow(request, data) }
        } finally {
            gate.release()
        }
    }

    private fun renderNow(request: DocumentGenerationRequest, data: ObjectNode): ByteArray {
        val draftId = request.draftId
        if (draftId != null) {
            return draftRenderer.render(request.catalogId, request.templateKey, request.variantKey, data, draftId, request.generationContext()).pdf
        }
        val version = requireNotNull(request.releaseVersion) { "A request bound to neither a release nor a draft predates 2.0 and has no form" }
        val tenant = mediator.query(GetTenant(id = request.tenantKey)) ?: throw TenantNotFoundException(request.tenantKey)
        val inputs = releaseRenderSource.resolve(tenant, ReleaseRef(request.catalogId, version), request.templateKey.value, request.variantKey.value, request.environmentKey)
        val effective = inputs.dataModel?.let { schemaValidator.applyDefaults(it, data) } ?: data

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(effective, Map::class.java) as Map<String, Any?>
        val out = ByteArrayOutputStream()
        generationService.renderPdfFromRelease(
            inputs = inputs,
            data = dataMap,
            outputStream = out,
            metadata = PdfMetadata(title = inputs.templateName, author = tenant.name),
            culture = localeResolver.resolveCulture(tenant, inputs.variantAttributes),
            watermarkText = DocumentPreviewRenderer.PREVIEW_WATERMARK,
        )
        return out.toByteArray()
    }

    companion object {
        const val PREVIEWS_PER_MINUTE = 20
        private const val MAX_TRACKED_FORMS = 10_000L
    }
}
