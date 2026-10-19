// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.common.ResourceModifiedException
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.handlers.CatalogDraftBar
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogParam
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.catalogVersion
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.stencilId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePermission
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.MarkStencilReady
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilUsagePage
import app.epistola.suite.stencils.queries.ListStencils
import app.epistola.suite.validation.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

/**
 * Where a stencil stands for its catalog's next release, and whether to offer "Mark ready".
 *
 * @property state how the stencil differs from the catalog's last release; null for a stencil of a
 *   subscribed catalog, which has no next release.
 * @property ready whether its working copy is marked ready as it is now.
 * @property offerMarkReady the one name the page gates "Mark ready" on: the viewer may edit
 *   stencils, the catalog is authored (so editable), and the working copy is not ready yet.
 */
data class StencilReadiness(
    val state: CatalogResourceState?,
    val ready: Boolean,
    val offerMarkReady: Boolean,
) {
    /** Whether releasing the catalog waits on this stencil. */
    val blocksRelease: Boolean get() = state != null && state != CatalogResourceState.RELEASED && state != CatalogResourceState.REMOVED && !ready
}

/**
 * Stencil handler serving both the management UI (HTMX) and editor callbacks (JSON).
 * Content negotiation: HTMX requests (HX-Request header) get fragments, others get JSON.
 */
@Component
class StencilHandler(
    private val objectMapper: ObjectMapper,
) {
    private fun ServerRequest.isHtmx(): Boolean = headers().firstHeader("HX-Request") != null

    // ── List & Search ──────────────────────────────────────────────────────

    fun list(request: ServerRequest): ServerResponse = ServerResponse.ok().page("stencils/list") { stencilPageModel(request) }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val searchTerm = request.queryParam("q")
        val segment = request.catalogParam()
        val catalogFilter = segment?.key
        // The editor searches across catalogs from inside a draft (`in=letters@2.0.0`): that
        // catalog's stencils are then the draft's, every other catalog's its current version's.
        val version = segment?.ref(tenantId.key) ?: request.catalogParam("in")?.ref(tenantId.key)

        if (!request.isHtmx()) {
            val stencils = ListStencils(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogFilter, catalogVersion = version).query()
            val items = stencils.map { s ->
                mapOf(
                    "id" to s.id.value,
                    "catalogKey" to s.catalogKey.value,
                    "name" to s.name,
                    "description" to s.description,
                    "tags" to s.tags,
                )
            }
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("items" to items))
        }

        val stencils = ListStencils(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogFilter, catalogVersion = version).query()
        return request.htmx {
            fragment("stencils/list", "rows") {
                "tenantId" to tenantId.key
                "stencils" to stencils
                "draftSegment" to segment
                // Lets the fragment tell "no matches" apart from "none yet". A search swaps only
                // this fragment, so the distinction has to reach it.
                "searchTerm" to searchTerm.orEmpty()
            }
            onNonHtmx { redirect(CatalogPages.list(request, tenantId.key, "stencils")) }
        }
    }

    // ── Create ─────────────────────────────────────────────────────────────

    /**
     * The catalog's stencils as the list page shows them: the page itself, and behind the create dialog
     * when `/new` is opened directly or a create is refused without HTMX. The segment may name a draft
     * (`letters@<draft>`); its rows are then the draft's.
     */
    private fun ModelBuilder.stencilPageModel(request: ServerRequest) {
        val tenantId = request.tenantId()
        // The filter may name a draft (`letters@2.0.0`); its rows are then the draft's.
        val segment = request.catalogSegment()
        val catalogs = ListCatalogs(tenantId.key).query()
        val stencils = ListStencils(tenantId = tenantId, catalogKey = segment.key, catalogVersion = segment.ref(tenantId.key)).query()
        val bar = segment.let { s -> CatalogDraftBar.of(tenantId.key, s) { "/tenants/${tenantId.key}/catalogs/${it.value}/stencils" } }
        // What the catalogs this one depends on hold of this kind, listed read-only below its own.
        val fromOtherCatalogs = FromOtherCatalogs.of(bar, { ListStencils(tenantId = tenantId, catalogKey = it).query() }) { s, c, n -> FromOtherCatalog(s.name, "/tenants/${tenantId.key}/catalogs/${c.value}/stencils/${s.id.value}", n) }
        "pageTitle" to "Stencils - Epistola"
        "tenantId" to tenantId.key
        "catalogs" to catalogs
        "selectedCatalog" to segment.value
        "draftSegment" to segment
        "draftBar" to bar
        "fromOtherCatalogs" to fromOtherCatalogs
        "stencils" to stencils
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.STENCIL_EDIT)
        // Fragment models are lazy, so only the branch that renders evaluates —
        // one ListCatalogs per request, shared by the list filter and the
        // dialog's authored-only <select>.
        val allCatalogs by lazy { ListCatalogs(tenantId.key).query() }
        val authoredCatalogs by lazy { allCatalogs.filter { it.type == CatalogType.AUTHORED } }
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("stencils/new", "dialog") {
                "tenantId" to tenantId.key
                // Opened from a list filtered to a draft, the dialog creates in that draft.
                // The catalog it was opened on, a draft or plain: a dialog opened from a catalog's page
                // creates there, rather than in whichever catalog sorts first.
                "draftSegment" to request.catalogParam()
                "authoredCatalogs" to authoredCatalogs
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onNonHtmx {
                page("stencils/list") {
                    stencilPageModel(request)
                    "openDialog" to true
                    "authoredCatalogs" to authoredCatalogs
                }
            }
        }
    }

    /**
     * Create a stencil. Supports two modes:
     * - Form POST (HTMX): creates stencil from form fields, redirects to detail
     * - JSON POST (editor): { id, name, catalogKey, content?, parameterSchema? } → the stencil
     *     with that working copy
     */
    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        if (!request.isHtmx()) {
            return createJson(request, tenantId)
        }

        return createForm(request, tenantId)
    }

    /**
     * The UI create-form path. Reached ONLY for HTMX requests: a genuinely non-HTMX
     * POST to this endpoint is the editor's JSON API (see [create] / [createJson]),
     * which is why this dialog drops the plain no-JS form-POST fallback the other
     * create dialogs keep — that fallback would collide with the JSON API.
     *
     * The `onNonHtmx { }` branches below therefore never serve the JSON API: within
     * [build] they fire only for **boosted** (HX-Boosted) or **history-restore**
     * (HX-History-Restore-Request) requests, which still carry `HX-Request: true` and
     * so land here rather than in [createJson]. Both are full-page browser
     * navigations that must NOT get a bare fragment — a stale tab left open across a
     * deploy submits this form as a boosted POST, and without an onNonHtmx fallback
     * [build] would throw (HTTP 500) even for a valid create. Mirrors
     * DocumentTemplateHandler.create.
     */
    private fun createForm(request: ServerRequest, tenantId: TenantId): ServerResponse {
        requirePermission(tenantId.key, Permission.STENCIL_EDIT)

        val form = request.form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
                // Folds the old "invalid StencilKey" branch into field validation
                // (same "Invalid stencil ID format" error) so all three failure
                // modes share one error path. Reported only when no rule above
                // failed first, so the specific message wins; what it adds beyond
                // them is StencilKey's reserved-word rule.
                asStencilId()
            }
            field("name") {
                required()
                maxLength(255)
            }
            field("description") {}
            // Declared so the comma-separated tags survive an error re-render
            // (preserved in formData); split into a list below for the command.
            field("tags") {}
        }

        val description = form["description"].trim().takeIf { it.isNotEmpty() }
        val tags = form["tags"].split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // Field validation (incl. catalog/CatalogKey and slug/StencilKey) and the
        // command-level failure (duplicate slug, name length) both land as `errors`
        // on the FormData, so they share one error path — mirroring
        // DocumentTemplateHandler.create.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                val segment = request.catalogSegment()
                CreateStencil(
                    id = StencilId(
                        StencilKey.validateOrNull(form["slug"])!!,
                        CatalogId(segment.key, tenantId),
                    ),
                    name = form["name"],
                    description = description,
                    tags = tags,
                    catalogVersion = segment.ref(tenantId.key),
                ).execute()
            }
        }

        if (result.hasErrors()) {
            // One ListCatalogs whichever branch renders (fragment models are lazy).
            val allCatalogs by lazy { ListCatalogs(tenantId.key).query() }
            val authoredCatalogs by lazy { allCatalogs.filter { it.type == CatalogType.AUTHORED } }
            // Re-render the form inside the dialog (retargeted to the form, not the
            // list) with inline errors + preserved values. The onNonHtmx fallback
            // (boosted / history-restore only — see the function KDoc) re-renders the
            // list page with the dialog embedded and its errors, so a stale-tab boosted
            // POST never throws.
            return request.htmx {
                dialogFieldErrors(
                    template = "stencils/new",
                    fragmentName = "stencil-form",
                    formTarget = "#create-stencil-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                    "draftSegment" to request.catalogParam()
                    "authoredCatalogs" to authoredCatalogs
                }
                onNonHtmx {
                    page(422, "stencils/list") {
                        stencilPageModel(request)
                        "openDialog" to true
                        "authoredCatalogs" to authoredCatalogs
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        // Success: soft-navigate to the newly created stencil's page via a boosted
        // body-swap (HX-Location) — no full-page reload. The dialog goes with the
        // swapped-out body; the list is not refreshed. onNonHtmx (boosted /
        // history-restore only) redirects to the same page.
        // Safe !!/of(): both fields were validated above and the create succeeded.
        val stencilKey = StencilKey.validateOrNull(form["slug"])!!
        val catalogKey = request.catalogSegment().value
        val destination = "/tenants/${tenantId.key}/catalogs/$catalogKey/stencils/$stencilKey"
        return request.htmx {
            dialogLocation(destination)
            onNonHtmx { redirect(destination) }
        }
    }

    private data class CreateStencilJsonRequest(
        val id: String,
        val name: String,
        val description: String? = null,
        val tags: List<String>? = null,
        val catalogKey: String? = null,
        val content: app.epistola.template.model.TemplateDocument? = null,
        val parameterSchema: tools.jackson.databind.JsonNode? = null,
    )

    private fun createJson(request: ServerRequest, tenantId: TenantId): ServerResponse {
        val body = request.body(String::class.java)
        val req = objectMapper.readValue(body, CreateStencilJsonRequest::class.java)

        // The editor sends its template's catalog segment, so a stencil made from a draft lands in it.
        val segment = req.catalogKey?.let { CatalogSegment.parse(it) }
            ?: return ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON).body(mapOf("error" to "catalogKey is required"))
        val stencilId = StencilId(StencilKey.of(req.id), CatalogId(segment.key, tenantId))

        CreateStencil(
            id = stencilId,
            name = req.name,
            description = req.description,
            tags = req.tags ?: emptyList(),
            content = req.content,
            parameterSchema = req.parameterSchema,
            catalogVersion = segment.ref(tenantId.key),
        ).execute()

        return ServerResponse.status(201)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("stencilId" to req.id, "catalogKey" to segment.key.value))
    }

    // ── Detail & Update & Delete ───────────────────────────────────────────

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val segment = request.catalogSegment()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val draftBar = CatalogDraftBar.of(tenantId.key, segment, CatalogDraftBar.inPath(request.path(), segment))
        val version = draftBar.ref

        val stencil = GetStencil(id = stencilId, catalogVersion = version).query()
            ?: return ServerResponse.notFound().build()

        val usagePage = GetStencilUsagePage(stencilId = stencilId, page = 1, catalogVersion = version).query()

        return ServerResponse.ok().page("stencils/detail") {
            "pageTitle" to "${stencil.name} - Epistola"
            "tenantId" to tenantId.key
            "catalogId" to catalogId.value
            "catalogSegment" to segment.value
            "draftSegment" to segment.takeIf { it.draft != null }
            "draftBar" to draftBar
            stencilModel(stencil, version = version)
            "parameterSchemaJson" to stencil.parameterSchema?.let { objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(it) }
            "usage" to usagePage.items
            "usagePage" to usagePage
        }
    }

    /** The stencil, whether the viewer may change it here, and its readiness. */
    private fun ModelBuilder.stencilModel(stencil: Stencil, error: String? = null, version: CatalogVersionRef? = null) {
        val editable = stencil.catalogType == CatalogType.AUTHORED &&
            AuthContext.from(SecurityContext.current(), stencil.tenantKey).has(Permission.STENCIL_EDIT)
        "stencil" to stencil
        "stencilEditable" to editable
        "readiness" to readinessOf(stencil, editable, version)
        if (error != null) "readinessError" to error
    }

    private fun readinessOf(stencil: Stencil, editable: Boolean, version: CatalogVersionRef?): StencilReadiness {
        if (stencil.catalogType != CatalogType.AUTHORED) return StencilReadiness(state = null, ready = false, offerMarkReady = false)
        val change = GetCatalogResourceChanges(stencil.tenantKey, stencil.catalogKey, catalogVersion = version).query()
            .resources.firstOrNull { it.type == "stencil" && it.slug == stencil.id.value }
        val ready = change?.ready == true
        return StencilReadiness(state = change?.state, ready = ready, offerMarkReady = editable && !ready)
    }

    /** `PATCH` with JSON metadata `{ name?, description?, clearDescription?, tags? }`. */
    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        data class UpdateRequest(
            val name: String? = null,
            val description: String? = null,
            val clearDescription: Boolean = false,
            val tags: List<String>? = null,
        )

        val body = request.body(String::class.java)
        val req = objectMapper.readValue(body, UpdateRequest::class.java)

        val stencil = UpdateStencil(
            id = stencilId,
            name = req.name,
            description = req.description,
            clearDescription = req.clearDescription,
            tags = req.tags,
            catalogVersion = request.catalogVersion(),
        ).execute() ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to stencil.id.value,
                    "name" to stencil.name,
                    "description" to stencil.description,
                    "tags" to stencil.tags,
                    "updatedAt" to stencil.updatedAt,
                ),
            )
    }

    /**
     * `POST …/settings` from the detail page's form: whether the stencil's content also sees the
     * using template's data. A checked box submits `readsTemplateData`, an unchecked one nothing.
     */
    fun updateSettings(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val readsTemplateData = request.params().getFirst("readsTemplateData")?.let { it != "false" } ?: false

        val stencil = UpdateStencil(id = stencilId, readsTemplateData = readsTemplateData, catalogVersion = request.catalogVersion()).execute()
            ?: return ServerResponse.notFound().build()
        return detailsFragment(request, stencil)
    }

    /**
     * `POST …/ready`: marks the working copy ready. From the detail page (HTMX) a refusal is shown in
     * the details panel; from the editor (JSON) it is a 400 with its `detail`.
     */
    fun markReady(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val error = try {
            MarkStencilReady(stencilId = stencilId, catalogVersion = request.catalogVersion()).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        if (!request.isHtmx()) {
            // The template editor marks the stencil it edits ready (JSON).
            val status = if (error == null) 200 else 400
            return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(if (error == null) mapOf("ready" to true) else mapOf("detail" to error))
        }
        val stencil = GetStencil(id = stencilId, catalogVersion = request.catalogVersion()).query()
            ?: return ServerResponse.notFound().build()
        return detailsFragment(request, stencil, error)
    }

    private fun detailsFragment(request: ServerRequest, stencil: Stencil, error: String? = null): ServerResponse = request.htmx {
        val segment = request.catalogSegment()
        fragment("stencils/detail", "details") {
            "tenantId" to stencil.tenantKey
            "catalogId" to stencil.catalogKey.value
            "catalogSegment" to segment.value
            stencilModel(stencil, error, segment.ref(stencil.tenantKey))
        }
        onFullPage { redirect("/tenants/${stencil.tenantKey}/catalogs/${segment.value}/stencils/${stencil.id}") }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val force = request.param("force").orElse("false").toBoolean()

        try {
            DeleteStencil(id = stencilId, force = force, catalogVersion = request.catalogVersion()).execute()
        } catch (e: app.epistola.suite.stencils.StencilInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // The catalog's rows, refreshed in place.
        val segment = request.catalogSegment()
        return request.htmx {
            fragment("stencils/list", "rows") {
                "tenantId" to tenantId.key
                "draftSegment" to segment
                "stencils" to ListStencils(tenantId = tenantId, catalogKey = segment.key, catalogVersion = segment.ref(tenantId.key)).query()
            }
            onNonHtmx { redirect(CatalogPages.list(request, tenantId.key, "stencils")) }
        }
    }

    // ── Usage ──────────────────────────────────────────────────────────────

    /** The variants that use this stencil, each with whether it still fits. HTMX → fragment; else JSON. */
    fun usageDetails(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val version = request.catalogVersion()
        val stencil = GetStencil(id = stencilId, catalogVersion = version).query()
            ?: return ServerResponse.notFound().build()

        val page = request.param("page").map { it.toIntOrNull() ?: 1 }.orElse(1)
        val usagePage = GetStencilUsagePage(stencilId = stencilId, page = page, catalogVersion = version).query()

        if (!request.isHtmx()) {
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "items" to usagePage.items.map { u ->
                            mapOf(
                                "templateId" to u.templateId.value,
                                "catalogKey" to u.catalogKey.value,
                                "templateName" to u.templateName,
                                "variantId" to u.variantId.value,
                                "instanceCount" to u.instanceCount,
                                "fits" to u.fits,
                                "problems" to u.problems,
                            )
                        },
                        "page" to usagePage.page,
                        "totalPages" to usagePage.totalPages,
                        "total" to usagePage.total,
                    ),
                )
        }

        return request.htmx {
            fragment("stencils/detail", "usage") {
                "tenantId" to tenantId.key
                "catalogId" to stencilId.catalogKey.value
                "catalogSegment" to request.catalogSegment().value
                "draftSegment" to request.catalogSegment().takeIf { it.draft != null }
                "stencil" to stencil
                "usage" to usagePage.items
                "usagePage" to usagePage
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/catalogs/${stencilId.catalogKey}/stencils/${stencilId.key}") }
        }
    }

    // ── Working copy (editor) ──────────────────────────────────────────────

    /** `GET …/content` (JSON): the stencil's working copy, which the template editor inlines. */
    fun getContent(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val stencil = GetStencil(id = stencilId, catalogVersion = request.catalogVersion()).query()
            ?: return ServerResponse.notFound().build()
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "stencilId" to stencil.id.value,
                    "catalogKey" to stencil.catalogKey.value,
                    "name" to stencil.name,
                    "editable" to (stencil.catalogType == CatalogType.AUTHORED),
                    "content" to stencil.content,
                    "parameterSchema" to stencil.parameterSchema,
                    "readsTemplateData" to stencil.readsTemplateData,
                    "revision" to stencil.revision,
                ),
            )
    }

    /**
     * `PUT …/content` (JSON `{ content, parameterSchema?, clearParameterSchema?, expectedRevision? }`):
     * saves the working copy from the template editor. An absent `parameterSchema` keeps the stored
     * one; `clearParameterSchema` removes it, for a stencil whose author removed every parameter. A
     * save against a revision someone else has moved on from is refused (412); the answer carries the
     * new `revision` otherwise.
     */
    fun updateContent(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        data class ContentRequest(
            val content: app.epistola.template.model.TemplateDocument,
            val parameterSchema: tools.jackson.databind.JsonNode? = null,
            val clearParameterSchema: Boolean = false,
            val expectedRevision: Long? = null,
        )

        val body = request.body(String::class.java)
        val req = objectMapper.readValue(body, ContentRequest::class.java)

        val saved = try {
            UpdateStencil(
                id = stencilId,
                content = req.content,
                parameterSchema = req.parameterSchema.takeUnless { req.clearParameterSchema },
                clearParameterSchema = req.clearParameterSchema,
                catalogVersion = request.catalogVersion(),
                expectedRevision = req.expectedRevision,
            ).execute()
        } catch (e: ResourceModifiedException) {
            return ServerResponse.status(HttpStatus.PRECONDITION_FAILED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to "Someone else saved this stencil after you opened it."))
        } ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("success" to true, "revision" to saved.revision))
    }
}
