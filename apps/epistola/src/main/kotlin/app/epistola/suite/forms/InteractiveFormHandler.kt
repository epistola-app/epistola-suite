// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.forms

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.documents.commands.CancelFormOutcome
import app.epistola.suite.documents.commands.CancelInteractiveForm
import app.epistola.suite.documents.commands.RedeemFormLink
import app.epistola.suite.documents.commands.SubmitFormOutcome
import app.epistola.suite.documents.commands.SubmitInteractiveForm
import app.epistola.suite.documents.interactive.FormDefinition
import app.epistola.suite.documents.interactive.FormOutcome
import app.epistola.suite.documents.interactive.FormSession
import app.epistola.suite.documents.interactive.InteractiveFormLinks
import app.epistola.suite.documents.interactive.InteractiveGenerationProperties
import app.epistola.suite.documents.queries.FormPreviewOutcome
import app.epistola.suite.documents.queries.GetInteractiveForm
import app.epistola.suite.documents.queries.InteractiveFormView
import app.epistola.suite.documents.queries.PreviewInteractiveForm
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.validation.TemplateDataAnalysis
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * The form page (contract ADR 0011, link rules amended by epistola-contract#134).
 *
 * - `GET /forms/{id}?authCode=…` redeems the link into a form session, sets it as a cookie scoped to
 *   this form's path and redirects to the clean URL, so the code never stays in the address bar, the
 *   history or a bookmark. A code works once.
 * - `GET /forms/{id}` shows the form, or how it ended, on that session, and renews it.
 * - `POST …/submit`, `…/cancel` and `…/preview` act on the session, each carrying the form guard the
 *   page rendered, so a post another site makes cannot act for the person.
 *
 * The core commands check the session themselves; this handler only moves it between the cookie and
 * them. Anything that is not a live session on an existing form gets one neutral page, so the page
 * does not tell which request ids exist. Neither the code nor the session is ever logged.
 */
@Component
class InteractiveFormHandler(
    private val links: InteractiveFormLinks,
    private val properties: InteractiveGenerationProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun page(request: ServerRequest): ServerResponse {
        val requestId = request.requestIdOrNull() ?: return gone()
        request.param(AUTH_CODE).orElse(null)?.let { code ->
            val session = RedeemFormLink(requestId, code).execute() ?: return gone()
            return ServerResponse.status(HttpStatus.SEE_OTHER)
                .location(URI.create("/forms/${requestId.value}"))
                .header(HttpHeaders.SET_COOKIE, sessionCookie(requestId, session).toString())
                .headers(::privatePage)
                .build()
        }
        val session = request.session(requestId) ?: return gone()
        return when (val view = GetInteractiveForm(requestId, session).query()) {
            null -> gone()
            is InteractiveFormView.Closed -> closed(view.outcome, view.returnUrl)
            is InteractiveFormView.Open -> open(view, HttpStatus.OK, view.data, view.analysis)
        }
    }

    fun submit(request: ServerRequest): ServerResponse {
        val (requestId, session) = request.guardedSession() ?: return gone()
        val data = request.formData() ?: return ServerResponse.badRequest().headers(::privatePage).build()
        return when (val outcome = SubmitInteractiveForm(requestId, session, data).execute()) {
            null -> gone()

            is SubmitFormOutcome.Submitted -> seeOther(outcome.redirectUrl ?: "/forms/${requestId.value}")

            is SubmitFormOutcome.Closed -> seeOther("/forms/${requestId.value}")

            is SubmitFormOutcome.Invalid -> {
                // Shown again with what the person typed and why it was refused; nothing was stored.
                val view = GetInteractiveForm(requestId, session).query() as? InteractiveFormView.Open ?: return gone()
                open(view, HttpStatus.UNPROCESSABLE_CONTENT, data, outcome.analysis)
            }
        }
    }

    fun cancel(request: ServerRequest): ServerResponse {
        val (requestId, session) = request.guardedSession() ?: return gone()
        return when (val outcome = CancelInteractiveForm(requestId, session).execute()) {
            null -> gone()
            is CancelFormOutcome.Cancelled -> seeOther(outcome.redirectUrl ?: "/forms/${requestId.value}")
            is CancelFormOutcome.Closed -> seeOther("/forms/${requestId.value}")
        }
    }

    fun preview(request: ServerRequest): ServerResponse {
        val requestId = request.requestIdOrNull() ?: return gone()
        val session = request.session(requestId) ?: return gone()
        val guard = request.headers().firstHeader(GUARD_HEADER)
        if (guard == null || !links.isFormGuard(requestId, guard)) return gone()
        val data = runCatching { objectMapper.readValue(request.body(String::class.java), ObjectNode::class.java) }.getOrNull()
            ?: return ServerResponse.badRequest().headers(::privatePage).build()
        return when (val outcome = PreviewInteractiveForm(requestId, session, data).query()) {
            null, is FormPreviewOutcome.Closed -> gone()

            is FormPreviewOutcome.Rendered -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
                .headers(::privatePage)
                .body(outcome.pdf)

            is FormPreviewOutcome.Invalid -> ServerResponse.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(::privatePage)
                .body(mapOf("problems" to problems(outcome.analysis)))

            FormPreviewOutcome.Limited -> ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .headers(::privatePage)
                .build()
        }
    }

    private fun open(view: InteractiveFormView.Open, status: HttpStatus, data: ObjectNode, analysis: TemplateDataAnalysis): ServerResponse {
        val model = mapOf(
            "requestId" to view.requestId.value.toString(),
            "title" to view.title,
            "instructions" to view.instructions,
            "links" to view.links,
            "lang" to (view.locale ?: "en"),
            "expiresAt" to view.expiresAt,
            "returnUrl" to view.returnUrl,
            "guard" to links.formGuard(view.requestId),
            "definition" to objectMapper.writeValueAsString(definitionJson(view.definition)),
            "data" to objectMapper.writeValueAsString(data),
            "problems" to objectMapper.writeValueAsString(problems(analysis)),
            "hasProblems" to (!analysis.valid && status != HttpStatus.OK),
        )
        return ServerResponse.status(status)
            .header(HttpHeaders.SET_COOKIE, sessionCookie(view.requestId, view.session).toString())
            .headers(::privatePage)
            .render("forms/form", model)
    }

    private fun closed(outcome: FormOutcome, returnUrl: String?): ServerResponse = ServerResponse.ok()
        .headers(::privatePage)
        .render("forms/closed", mapOf("outcome" to outcome.name, "returnUrl" to returnUrl))

    /** One page for every way a link or session can fail, so nothing tells which forms exist. */
    private fun gone(): ServerResponse {
        log.debug("Interactive form request refused: no live session on an existing form")
        return ServerResponse.status(HttpStatus.NOT_FOUND).headers(::privatePage).render("forms/gone", emptyMap<String, Any>())
    }

    private fun seeOther(location: String): ServerResponse = ServerResponse.status(HttpStatus.SEE_OTHER)
        .location(URI.create(location))
        .headers(::privatePage)
        .build()

    private fun definitionJson(definition: FormDefinition): Map<String, Any?> = when (definition) {
        is FormDefinition.Generated -> mapOf("kind" to definition.kind, "schema" to definition.schema)
    }

    /**
     * The analysis as the page marks fields: a JSON Pointer, a message and a kind for each problem.
     * The page holds back a `missing` field's mark until the person has been there or tried to
     * submit; an `invalid` value the caller sent shows at once.
     */
    private fun problems(analysis: TemplateDataAnalysis): List<Map<String, String>> = analysis.missingFields.filter { it.required }.map { mapOf("path" to it.path, "message" to "Fill this in.", "kind" to "missing") } +
        analysis.invalidFields.map { mapOf("path" to it.path, "message" to it.message, "kind" to "invalid") }

    private fun sessionCookie(requestId: GenerationRequestKey, session: FormSession): ResponseCookie = ResponseCookie.from(SESSION_COOKIE, session.token)
        .path("/forms/${requestId.value}")
        .httpOnly(true)
        .secure(properties.publicBaseUrl?.startsWith("https://") == true)
        // Lax: sent when the person arrives from the caller's page (a top-level GET), never on a
        // post another site makes.
        .sameSite("Lax")
        .maxAge(Duration.between(EpistolaClock.offsetDateTime(), session.expiresAt).coerceAtLeast(Duration.ZERO))
        .build()

    private fun ServerRequest.requestIdOrNull(): GenerationRequestKey? = runCatching { GenerationRequestKey.of(UUID.fromString(pathVariable("requestId"))) }.getOrNull()

    private fun ServerRequest.session(requestId: GenerationRequestKey): String? = cookies()[SESSION_COOKIE]?.firstOrNull()?.value?.takeIf { links.isSession(requestId, it) }

    /** The request id and session of a form post, when it carries the guard the page rendered. */
    private fun ServerRequest.guardedSession(): Pair<GenerationRequestKey, String>? {
        val requestId = requestIdOrNull() ?: return null
        val session = session(requestId) ?: return null
        val guard = param("guard").orElse(null) ?: return null
        return if (links.isFormGuard(requestId, guard)) requestId to session else null
    }

    private fun ServerRequest.formData(): ObjectNode? = param("data").orElse(null)?.let { runCatching { objectMapper.readValue(it, ObjectNode::class.java) }.getOrNull() }

    companion object {
        const val SESSION_COOKIE = "epistola_form"
        const val AUTH_CODE = "authCode"
        const val GUARD_HEADER = "X-Form-Guard"

        /** No referrer leaves the page (links on it must not learn its URL), and nothing of it is cached. */
        fun privatePage(headers: HttpHeaders) {
            headers.set("Referrer-Policy", "no-referrer")
            headers.setCacheControl("no-store")
        }
    }
}
