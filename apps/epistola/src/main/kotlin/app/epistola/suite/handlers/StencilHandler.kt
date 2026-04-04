package app.epistola.suite.handlers

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VersionKey
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
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilVersion
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.stencils.queries.ListStencils
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

@Component
class StencilHandler(
    private val objectMapper: ObjectMapper,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencils = ListStencils(tenantId = tenantId).query()
        return ServerResponse.ok().page("stencils/list") {
            "pageTitle" to "Stencils - Epistola"
            "tenantId" to tenantId.key
            "stencils" to stencils
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val searchTerm = request.queryParam("q")
        val stencils = ListStencils(tenantId = tenantId, searchTerm = searchTerm).query()

        // JSON response for editor callbacks (Accept: application/json)
        val acceptsJson = request.headers().accept().any { it.isCompatibleWith(MediaType.APPLICATION_JSON) }
        if (acceptsJson) {
            return searchJson(request)
        }

        return request.htmx {
            fragment("stencils/list", "rows") {
                "tenantId" to tenantId.key
                "stencils" to stencils
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/stencils") }
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        return ServerResponse.ok().page("stencils/new") {
            "pageTitle" to "New Stencil - Epistola"
            "tenantId" to tenantId.key
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
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

        if (form.hasErrors()) {
            return ServerResponse.ok().page("stencils/new") {
                "pageTitle" to "New Stencil - Epistola"
                "tenantId" to tenantId.key
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
                id = StencilId(stencilKey, tenantId),
                name = name,
                description = description,
                tags = tags,
            ).execute()
        }

        if (result.hasErrors()) {
            return ServerResponse.ok().page("stencils/new") {
                "pageTitle" to "New Stencil - Epistola"
                "tenantId" to tenantId.key
                "formData" to result.formData
                "errors" to result.errors
            }
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/stencils/$stencilKey")
            .build()
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val stencil = GetStencil(id = stencilId).query()
            ?: return ServerResponse.notFound().build()

        val versions = ListStencilVersions(stencilId = stencilId).query()

        return ServerResponse.ok().page("stencils/detail") {
            "pageTitle" to "${stencil.name} - Epistola"
            "tenantId" to tenantId.key
            "stencil" to stencil
            "versions" to versions
        }
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        data class UpdateStencilRequest(
            val name: String? = null,
            val description: String? = null,
            val clearDescription: Boolean = false,
            val tags: List<String>? = null,
        )

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateStencilRequest::class.java)

        val stencil = UpdateStencil(
            id = stencilId,
            name = updateRequest.name,
            description = updateRequest.description,
            clearDescription = updateRequest.clearDescription,
            tags = updateRequest.tags,
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

    /** JSON endpoint for the editor's stencil search callback. */
    fun searchJson(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val searchTerm = request.queryParam("q")
        val stencils = ListStencils(tenantId = tenantId, searchTerm = searchTerm).query()

        val items = stencils.map { stencil ->
            val versions = ListStencilVersions(stencilId = StencilId(stencil.id, tenantId)).query()
            val latestPublished = versions
                .filter { it.status == app.epistola.suite.stencils.model.StencilVersionStatus.PUBLISHED }
                .maxByOrNull { it.id.value }
                ?.id?.value
            mapOf(
                "id" to stencil.id.value,
                "name" to stencil.name,
                "description" to stencil.description,
                "tags" to stencil.tags,
                "latestPublishedVersion" to latestPublished,
            )
        }

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("items" to items))
    }

    /** JSON endpoint for the editor to fetch a specific stencil version. */
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

    fun createVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()

        CreateStencilVersion(stencilId = stencilId).execute()
            ?: return ServerResponse.notFound().build()

        return versionListFragment(request, tenantId, stencilId)
    }

    fun publishVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val versionId = request.pathVariable("versionId").toIntOrNull()
            ?: return ServerResponse.badRequest().build()
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilId)

        PublishStencilVersion(versionId = versionIdComposite).execute()
            ?: return ServerResponse.notFound().build()

        return versionListFragment(request, tenantId, stencilId)
    }

    fun archiveVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val stencilId = request.stencilId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val versionId = request.pathVariable("versionId").toIntOrNull()
            ?: return ServerResponse.badRequest().build()
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilId)

        ArchiveStencilVersion(versionId = versionIdComposite).execute()
            ?: return ServerResponse.notFound().build()

        return versionListFragment(request, tenantId, stencilId)
    }

    private fun versionListFragment(request: ServerRequest, tenantId: TenantId, stencilId: StencilId): ServerResponse {
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

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
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
}
