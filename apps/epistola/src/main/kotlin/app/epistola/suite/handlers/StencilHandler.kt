package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.stencilId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.ArchiveStencilVersion
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.commands.UpdateStencilDraft
import app.epistola.suite.stencils.commands.UpdateStencilInTemplate
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilUsageDetails
import app.epistola.suite.stencils.queries.GetStencilVersion
import app.epistola.suite.stencils.queries.ListStencilSummaries
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.stencils.queries.ListStencils
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

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

    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogFilter = request.param("catalog").orElse(null)?.ifBlank { null }?.let { CatalogKey.of(it) }
        val catalogs = ListCatalogs(tenantId.key).query()
        val stencils = ListStencils(tenantId = tenantId, catalogKey = catalogFilter).query()
        return ServerResponse.ok().page("stencils/list") {
            "pageTitle" to "Stencils - Epistola"
            "tenantId" to tenantId.key
            "catalogs" to catalogs
            "selectedCatalog" to (catalogFilter?.value ?: "")
            "stencils" to stencils
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val searchTerm = request.queryParam("q")
        val catalogFilter = request.queryParam("catalog")?.ifBlank { null }?.let { CatalogKey.of(it) }

        if (!request.isHtmx()) {
            val summaries = ListStencilSummaries(tenantId = tenantId, searchTerm = searchTerm).query()
            val items = summaries.map { s ->
                mapOf(
                    "id" to s.id.value,
                    "catalogKey" to s.catalogKey.value,
                    "name" to s.name,
                    "description" to s.description,
                    "tags" to s.tags,
                    "latestPublishedVersion" to s.latestPublishedVersion,
                    "latestVersion" to s.latestVersion,
                )
            }
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("items" to items))
        }

        val stencils = ListStencils(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogFilter).query()
        return request.htmx {
            fragment("stencils/list", "rows") {
                "tenantId" to tenantId.key
                "stencils" to stencils
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/stencils") }
        }
    }

    // ── Create ─────────────────────────────────────────────────────────────

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }
        return ServerResponse.ok().page("stencils/new") {
            "pageTitle" to "New Stencil - Epistola"
            "tenantId" to tenantId.key
            "catalogs" to catalogs
        }
    }

    /**
     * Create a stencil. Supports two modes:
     * - Form POST (HTMX): creates stencil from form fields, redirects to detail
     * - JSON POST (editor): { id, name, content?, publish? }
     *     content → creates draft v1 with that content
     *     publish → also publishes v1
     */
    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        if (!request.isHtmx()) {
            return createJson(request, tenantId)
        }

        return createForm(request, tenantId)
    }

    private fun createForm(request: ServerRequest, tenantId: TenantId): ServerResponse {
        val form = request.form {
            field("catalog") {}
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
            }
            field("name") {
                required()
                maxLength(255)
            }
            field("description") {}
        }

        val catalogKey = CatalogKey.of(form.formData["catalog"]?.ifBlank { null } ?: CatalogKey.DEFAULT.value)
        val catalogs = ListCatalogs(tenantId.key).query().filter { it.type == CatalogType.AUTHORED }

        if (form.hasErrors()) {
            return ServerResponse.ok().page("stencils/new") {
                "pageTitle" to "New Stencil - Epistola"
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "formData" to form.formData
                "errors" to form.errors
            }
        }

        val stencilKey = StencilKey.validateOrNull(form["slug"])
        if (stencilKey == null) {
            val errors = mapOf("slug" to "Invalid stencil ID format")
            return ServerResponse.ok().page("stencils/new") {
                "pageTitle" to "New Stencil - Epistola"
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "formData" to form.formData
                "errors" to errors
            }
        }

        val name = form["name"]
        val description = request.params().getFirst("description")?.trim()?.takeIf { it.isNotEmpty() }
        val tagsRaw = request.params().getFirst("tags")?.trim()?.takeIf { it.isNotEmpty() }
        val tags = tagsRaw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val result = form.executeOrFormError {
            CreateStencil(
                id = StencilId(stencilKey, CatalogId(catalogKey, tenantId)),
                name = name,
                description = description,
                tags = tags,
            ).execute()
        }

        if (result.hasErrors()) {
            return ServerResponse.ok().page("stencils/new") {
                "pageTitle" to "New Stencil - Epistola"
                "tenantId" to tenantId.key
                "catalogs" to catalogs
                "formData" to result.formData
                "errors" to result.errors
            }
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/stencils/$catalogKey/$stencilKey")
            .build()
    }

    private data class CreateStencilJsonRequest(
        val id: String,
        val name: String,
        val description: String? = null,
        val tags: List<String>? = null,
        val content: app.epistola.template.model.TemplateDocument? = null,
        val publish: Boolean = false,
    )

    private fun createJson(request: ServerRequest, tenantId: TenantId): ServerResponse {
        val body = request.body(String::class.java)
        val req = objectMapper.readValue(body, CreateStencilJsonRequest::class.java)

        val stencilId = StencilId(StencilKey.of(req.id), CatalogId.default(tenantId))

        CreateStencil(
            id = stencilId,
            name = req.name,
            description = req.description,
            tags = req.tags ?: emptyList(),
            content = req.content,
        ).execute()

        var publishedVersion: Int? = null
        if (req.publish && req.content != null) {
            val versionIdComposite = StencilVersionId(VersionKey.of(1), stencilId)
            PublishStencilVersion(versionId = versionIdComposite).execute()
            publishedVersion = 1
        }

        return ServerResponse.status(201)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("stencilId" to req.id, "version" to (publishedVersion ?: 1)))
    }

    // ── Detail & Update & Delete ───────────────────────────────────────────

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val stencil = GetStencil(id = stencilId).query()
            ?: return ServerResponse.notFound().build()

        val versions = ListStencilVersions(stencilId = stencilId).query()
        val usage = GetStencilUsageDetails(stencilId = stencilId).query()

        return ServerResponse.ok().page("stencils/detail") {
            "pageTitle" to "${stencil.name} - Epistola"
            "tenantId" to tenantId.key
            "catalogId" to catalogId.value
            "stencil" to stencil
            "versions" to versions
            "usage" to usage
        }
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
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
        ).execute() ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to stencil.id.value,
                    "name" to stencil.name,
                    "description" to stencil.description,
                    "tags" to stencil.tags,
                    "lastModified" to stencil.lastModified,
                ),
            )
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        DeleteStencil(id = stencilId).execute()

        val stencils = ListStencils(tenantId = tenantId).query()
        return request.htmx {
            fragment("stencils/list", "rows") {
                "tenantId" to tenantId.key
                "stencils" to stencils
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/stencils") }
        }
    }

    // ── Usage & Upgrade ──────────────────────────────────────────────────

    /** Usage details: which templates use this stencil, with version info. */
    fun usageDetails(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val usage = GetStencilUsageDetails(stencilId = stencilId).query()

        if (!request.isHtmx()) {
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "items" to usage.map { u ->
                            mapOf(
                                "templateId" to u.templateId.value,
                                "templateName" to u.templateName,
                                "variantId" to u.variantId.value,
                                "versionId" to u.versionId.value,
                                "versionStatus" to u.versionStatus,
                                "stencilVersion" to u.stencilVersion,
                                "instanceCount" to u.instanceCount,
                            )
                        },
                    ),
                )
        }

        val stencil = GetStencil(id = stencilId).query()
        val versions = ListStencilVersions(stencilId = stencilId).query()
        return request.htmx {
            fragment("stencils/detail", "usage") {
                "tenantId" to tenantId.key
                "stencil" to stencil
                "versions" to versions
                "usage" to usage
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/stencils/${stencilId.key}") }
        }
    }

    /** Upgrade a stencil in a specific template variant's draft. */
    fun upgradeInTemplate(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        data class UpgradeRequest(
            val templateId: String,
            val variantId: String,
            val newVersion: Int,
        )

        val body = request.body(String::class.java)
        val req = objectMapper.readValue(body, UpgradeRequest::class.java)

        val templateId = TemplateId(TemplateKey.of(req.templateId), CatalogId.default(tenantId))
        val variantId = VariantId(VariantKey.of(req.variantId), templateId)

        val count = UpdateStencilInTemplate(
            variantId = variantId,
            stencilId = stencilId,
            newVersion = req.newVersion,
        ).execute()

        if (count == null) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to "No draft version found for this template variant"))
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("upgraded" to count))
    }

    // ── Versions ───────────────────────────────────────────────────────────

    /** List versions. HTMX → fragment; JSON → version list. */
    fun listVersions(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val versions = ListStencilVersions(stencilId = stencilId).query()

        if (!request.isHtmx()) {
            val items = versions.map { v ->
                mapOf(
                    "version" to v.id.value,
                    "status" to v.status.name.lowercase(),
                    "createdAt" to v.createdAt.toString(),
                    "publishedAt" to v.publishedAt?.toString(),
                )
            }
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("items" to items))
        }

        return versionListFragment(request, tenantId, stencilId)
    }

    /** Get a specific version's content (JSON only). */
    fun getVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val versionId = request.pathVariable("versionId").toIntOrNull()
            ?: return ServerResponse.badRequest().build()
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilId)

        val version = GetStencilVersion(versionId = versionIdComposite).query()
            ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to version.id.value,
                    "stencilId" to version.stencilKey.value,
                    "status" to version.status.name.lowercase(),
                    "content" to version.content,
                ),
            )
    }

    /**
     * Create a version. Idempotent — returns existing draft if one exists.
     * HTMX → version list fragment; JSON → { version, status }.
     * Optional JSON payload: { content?, publish? }
     */
    fun createVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val draft = CreateStencilVersion(stencilId = stencilId).execute()
            ?: return ServerResponse.notFound().build()

        if (!request.isHtmx()) {
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("version" to draft.id.value, "status" to draft.status.name.lowercase()))
        }

        return versionListFragment(request, tenantId, stencilId)
    }

    /** Publish a version. HTMX → fragment; JSON → { version, status }. */
    fun publishVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val versionId = request.pathVariable("versionId").toIntOrNull()
            ?: return ServerResponse.badRequest().build()
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilId)

        val published = PublishStencilVersion(versionId = versionIdComposite).execute()
            ?: return ServerResponse.notFound().build()

        if (!request.isHtmx()) {
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("version" to published.id.value, "status" to "published"))
        }

        return versionListFragment(request, tenantId, stencilId)
    }

    /** Archive a version. HTMX → fragment; JSON → { version, status }. */
    fun archiveVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val versionId = request.pathVariable("versionId").toIntOrNull()
            ?: return ServerResponse.badRequest().build()
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilId)

        val archived = ArchiveStencilVersion(versionId = versionIdComposite).execute()
            ?: return ServerResponse.notFound().build()

        if (!request.isHtmx()) {
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("version" to archived.id.value, "status" to "archived"))
        }

        return versionListFragment(request, tenantId, stencilId)
    }

    // ── Draft content ──────────────────────────────────────────────────────

    /** Save content to the current draft version (editor auto-save). */
    fun updateDraft(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        data class DraftRequest(
            val content: app.epistola.template.model.TemplateDocument,
        )

        val body = request.body(String::class.java)
        val req = objectMapper.readValue(body, DraftRequest::class.java)

        // Ensure a draft exists (idempotent). Pass content so it works even
        // for brand-new stencils with no published versions to copy from.
        val draft = CreateStencilVersion(stencilId = stencilId, content = req.content).execute()
            ?: return ServerResponse.notFound().build()

        // Always update — CreateStencilVersion returns existing draft without updating content
        UpdateStencilDraft(
            versionId = StencilVersionId(draft.id, stencilId),
            content = req.content,
        ).execute()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("version" to draft.id.value))
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private fun versionListFragment(
        request: ServerRequest,
        tenantId: TenantId,
        stencilId: StencilId,
    ): ServerResponse {
        val versions = ListStencilVersions(stencilId = stencilId).query()
        return request.htmx {
            fragment("stencils/detail", "versions") {
                "tenantId" to tenantId.key
                "stencil" to GetStencil(id = stencilId).query()
                "versions" to versions
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/stencils/${stencilId.key}") }
        }
    }
}
