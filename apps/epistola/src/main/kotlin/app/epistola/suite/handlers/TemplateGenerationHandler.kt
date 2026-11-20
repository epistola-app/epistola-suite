// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.CatalogNotReleasedException
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.GenerationJobTokens
import app.epistola.suite.documents.NoReleaseDeployedException
import app.epistola.suite.documents.ReleaseNotFoundException
import app.epistola.suite.documents.TemplateNotInReleaseException
import app.epistola.suite.documents.commands.CancelGenerationJob
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.interactive.InteractiveGeneration
import app.epistola.suite.documents.interactive.InteractiveGenerationProperties
import app.epistola.suite.documents.interactive.InteractiveTrigger
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetDocumentMetadata
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.generation.release.EnvironmentUsesDraftException
import app.epistola.suite.htmx.FormData
import app.epistola.suite.htmx.catalog
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePermission
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.templates.TemplateDetailContext
import app.epistola.suite.templates.TemplateDetailHelper
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.ValidationException
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/**
 * "Generate document" on the template page: the dialog that asks for an environment, a variant, the
 * data and whether a person completes it in a form first (contract ADR 0011), and the panel that
 * follows the job it started until its document can be downloaded.
 *
 * The UI cannot call the REST API, so the dialog dispatches the same [GenerateDocument]. The job
 * routes never take a bare request id: a person here may hold `DOCUMENT_VIEW`, which reads every
 * document in the tenant, so they take a [GenerationJobTokens] token naming the request and the
 * signed-in person, and answer 404 for anything else. The form returns the browser to the template
 * page with that token.
 */
@Component
class TemplateGenerationHandler(
    private val detailHelper: TemplateDetailHelper,
    private val tokens: GenerationJobTokens,
    private val interactiveProperties: InteractiveGenerationProperties,
    private val contentStore: DocumentContentStore,
    private val objectMapper: ObjectMapper,
) {

    /** `GET …/templates/{id}/generate`: the dialog. */
    fun dialog(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()
        requirePermission(ctx.templateId.tenantKey, Permission.DOCUMENT_GENERATE)
        val choices = choices(request, ctx)
        val example = GetDataContract(templateId = ctx.templateId, catalogVersion = ctx.catalogVersion).query()?.dataExamples?.firstOrNull()
        val formData = mapOf(
            "target" to (choices.targets.firstOrNull()?.value ?: ""),
            "variant" to (choices.variants.firstOrNull { it.isDefault }?.id?.value ?: ""),
            "data" to (example?.data?.let { objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(it) } ?: "{}"),
            "trigger" to "",
        )
        return request.htmx {
            fragment(DIALOG, "dialog") {
                dialogModel(ctx, choices).forEach { (k, v) -> k to v }
                "formData" to formData
                "errors" to emptyMap<String, String>()
            }
            onFullPage { redirect(templatePage(ctx)) }
        }
    }

    /**
     * `POST …/templates/{id}/generate`: starts the job. With a form, the browser goes to it; without
     * one, back to the template page, where the panel follows the job.
     */
    fun generate(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()
        val tenantKey = ctx.templateId.tenantKey
        requirePermission(tenantKey, Permission.DOCUMENT_GENERATE)
        val choices = choices(request, ctx)
        val catalog = request.catalog(request.tenantId())

        val form = request.form {
            field("target") { required() }
            field("variant") { required() }
            field("data") { maxLength(MAX_DATA) }
            field("trigger") {}
        }
        val data = if (form.hasErrors()) null else parseData(form["data"])
        // Only what the dialog offered: an environment serving the catalog, its latest release, a variant of the template.
        val target = choices.targets.firstOrNull { it.value == form["target"] }
        val variant = form.getVariantId("variant")?.takeIf { key -> choices.variants.any { it.id == key } }
        var started: DocumentGenerationRequest? = null
        var token: String? = null
        val result = when {
            form.hasErrors() -> form

            target == null -> form.withError("target", "Choose where to generate from.")

            variant == null -> form.withError("variant", "Choose a variant of this template.")

            data == null -> form.withError("data", "Enter the data as a JSON object, such as {\"name\": \"Jane\"}.")

            else -> {
                val requestId = GenerationRequestKey.generate()
                val user = SecurityContext.current().userId
                token = tokens.issue(requestId, user, EpistolaClock.offsetDateTime().plus(interactiveProperties.defaultExpiry).plus(TOKEN_GRACE))
                val trigger = form["trigger"].takeIf { choices.formsAvailable }?.let { InteractiveTrigger.entries.firstOrNull { t -> t.name == it } }
                try {
                    started = GenerateDocument(
                        catalog = catalog,
                        templateId = ctx.templateId.key,
                        variantId = variant,
                        environmentId = target.environment,
                        data = data,
                        filename = null,
                        interactive = trigger?.let {
                            InteractiveGeneration(it, returnUrl = absolute(templatePage(ctx, token)), title = ctx.template.name)
                        },
                        requestId = requestId,
                    ).execute()
                    form
                } catch (e: ValidationException) {
                    form.withError(e.field.takeIf { it in FIELDS } ?: "target", e.message)
                } catch (e: RuntimeException) {
                    val message = generationProblem(e) ?: throw e
                    form.withError("target", message)
                }
            }
        }

        if (result.hasErrors()) {
            return request.htmx {
                dialogFieldErrors(template = DIALOG, fragmentName = "generate-form", formTarget = "#generate-document-form", formData = result) {
                    dialogModel(ctx, choices).forEach { (k, v) -> k to v }
                }
                onFullPage { redirect(templatePage(ctx)) }
            }
        }
        val formUrl = started!!.interactive?.url
        val next = formUrl ?: templatePage(ctx, token)
        return request.htmx {
            dialogRedirect(next)
            onFullPage { redirect(next) }
        }
    }

    /** `GET …/templates/{id}/jobs/{token}`: the panel for the job [token] names, refreshed while it runs. */
    fun job(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()
        val job = ownJob(request, ctx.templateId.tenantKey, ctx) ?: return ServerResponse.notFound().build()
        return request.htmx {
            fragment(DIALOG, "job-panel") {
                "job" to JobPanel.of(job)
                "jobUrl" to "${templatePage(ctx)}/jobs/${request.pathVariable("token")}"
            }
            onFullPage { redirect(templatePage(ctx, request.pathVariable("token"))) }
        }
    }

    /** `GET …/templates/{id}/jobs/{token}/document`: the job's PDF, once rendered. */
    fun document(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()
        val tenantKey = ctx.templateId.tenantKey
        val job = ownJob(request, tenantKey, ctx) ?: return ServerResponse.notFound().build()
        val documentKey = job.documentKey?.takeIf { job.status == RequestStatus.COMPLETED } ?: return ServerResponse.notFound().build()
        val metadata = GetDocumentMetadata(tenantKey, documentKey).query() ?: return ServerResponse.notFound().build()
        val stored = contentStore.get(ContentKey.document(tenantKey, documentKey)) ?: return ServerResponse.notFound().build()
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(metadata.sizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(metadata.filename, Charsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(InputStreamResource(stored.content))
    }

    /** `POST …/templates/{id}/jobs/{token}/cancel`: cancels the job, and shows the panel again. */
    fun cancel(request: ServerRequest): ServerResponse {
        val ctx = detailHelper.loadContext(request) ?: return ServerResponse.notFound().build()
        val tenantKey = ctx.templateId.tenantKey
        val job = ownJob(request, tenantKey, ctx) ?: return ServerResponse.notFound().build()
        if (job.isCancellable) CancelGenerationJob(tenantKey, job.id).execute()
        return job(request)
    }

    /**
     * The job the path's token lets the signed-in person follow, when it is a job of this tenant and
     * template; null otherwise, which every route answers with 404 without saying why.
     */
    private fun ownJob(request: ServerRequest, tenantKey: TenantKey, ctx: TemplateDetailContext): DocumentGenerationRequest? {
        val user = SecurityContext.currentOrNull()?.userId ?: return null
        val requestId = tokens.verify(request.pathVariable("token"), user) ?: return null
        val job = GetGenerationJob(tenantKey, requestId).query()?.request ?: return null
        return job.takeIf { it.templateKey == ctx.templateId.key }
    }

    /** What the dialog offers: where to generate from, which variant, and whether a form may be asked for. */
    private fun choices(request: ServerRequest, ctx: TemplateDetailContext): GenerateChoices {
        val catalog: CatalogId = request.catalog(request.tenantId())
        val environments = ListDeployments(ctx.templateId.tenantKey, catalog).query()
            .distinctBy { it.environmentKey }
            .map { GenerateChoices.Target("$ENVIRONMENT${it.environmentKey.value}", it.environmentKey.value, it.version, it.environmentKey) }
        val latest = ctx.draftBar?.latestPublished
        val targets = listOfNotNull(latest?.let { GenerateChoices.Target(LATEST, "Latest release", it) }) + environments
        val variants = GetVariantSummaries(templateId = ctx.templateId, catalogVersion = ctx.catalogVersion).query()
        val formsAvailable = interactiveProperties.publicBaseUrl != null &&
            ResolveFeatureToggles(ctx.templateId.tenantKey).query()[KnownFeatures.INTERACTIVE_GENERATION] == true
        return GenerateChoices(targets, variants, formsAvailable)
    }

    private fun dialogModel(ctx: TemplateDetailContext, choices: GenerateChoices): Map<String, Any?> = mapOf(
        "tenantId" to ctx.tenantId,
        "catalogSegment" to ctx.catalogSegment,
        "template" to ctx.template,
        "choices" to choices,
        "triggers" to TRIGGERS,
    )

    private fun parseData(text: String): ObjectNode? = try {
        objectMapper.readTree(text.ifBlank { "{}" }) as? ObjectNode
    } catch (_: JacksonException) {
        null
    }

    private fun generationProblem(e: RuntimeException): String? = when (e) {
        is CatalogNotReleasedException, is ReleaseNotFoundException, is NoReleaseDeployedException,
        is EnvironmentNotFoundException, is EnvironmentUsesDraftException, is TemplateNotInReleaseException,
        -> e.message

        else -> null
    }

    private fun templatePage(ctx: TemplateDetailContext, token: String? = null): String = "/tenants/${ctx.tenantId}/catalogs/${ctx.catalogSegment}/templates/${ctx.templateId.key.value}" + (token?.let { "?job=$it" } ?: "")

    private fun absolute(path: String): String = requireNotNull(interactiveProperties.publicBaseUrl).trimEnd('/') + path

    private fun FormData.withError(field: String, message: String): FormData = FormData(formData, errors + (field to message))

    private companion object {
        const val DIALOG = "templates/detail/generate"
        const val ENVIRONMENT = "environment:"
        const val LATEST = "latest"
        const val MAX_DATA = 100_000
        val FIELDS = setOf("target", "variant", "data", "trigger")

        /** How long after a form's default wait the person can still come back to the job. */
        val TOKEN_GRACE: Duration = Duration.ofDays(1)

        val TRIGGERS = listOf(
            GenerateChoices.Trigger("", "No, generate straight away"),
            GenerateChoices.Trigger(InteractiveTrigger.INCOMPLETE.name, "When data is missing or invalid"),
            GenerateChoices.Trigger(InteractiveTrigger.ALWAYS.name, "Always"),
        )
    }
}

/** What the "Generate document" dialog offers. */
data class GenerateChoices(
    val targets: List<Target>,
    val variants: List<VariantSummary>,
    /** Whether the tenant has interactive generation on, so the dialog may ask for a form. */
    val formsAvailable: Boolean,
) {
    /** Where to generate from: the catalog's latest release, or the release an environment serves. */
    data class Target(val value: String, val label: String, val version: String?, val environment: EnvironmentKey? = null)

    data class Trigger(val value: String, val label: String)

    /** Nothing to generate from: no release, and no environment serving the catalog. */
    val nothingToGenerate: Boolean get() = targets.isEmpty()
}

/** The job panel on the template page: what the job is doing and what the person can do next. */
data class JobPanel(
    val status: RequestStatus,
    /** A fresh link to the form while the job waits for it. */
    val formUrl: String?,
    val cancellable: Boolean,
    val downloadable: Boolean,
    val errorMessage: String?,
    val cancelReason: String?,
    val editedFields: List<String>?,
) {
    /** Still changing: the panel refreshes itself. */
    val running: Boolean get() = status == RequestStatus.PENDING || status == RequestStatus.IN_PROGRESS

    companion object {
        fun of(job: DocumentGenerationRequest) = JobPanel(
            status = job.status,
            formUrl = job.interactive?.url?.takeIf { job.status == RequestStatus.AWAITING_INPUT },
            cancellable = job.isCancellable,
            downloadable = job.status == RequestStatus.COMPLETED && job.documentKey != null,
            errorMessage = job.errorMessage?.takeIf { job.status == RequestStatus.FAILED },
            cancelReason = job.cancelReason?.name,
            editedFields = job.interactive?.editedFields,
        )
    }
}
