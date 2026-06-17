package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.environmentId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.htmxCurrentUrl
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.urlWithCreateParam
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class EnvironmentHandler {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        val environments = ListEnvironments(tenantId = tenantId).query()
        val createOpen = request.queryParam("create") != null
        return ServerResponse.ok().page("environments/list") {
            "pageTitle" to "Environments - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "environments" to environments
            "createOpen" to createOpen
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val searchTerm = request.queryParam("q")
        val environments = ListEnvironments(tenantId = tenantId, searchTerm = searchTerm).query()
        return request.htmx {
            fragment("environments/list", "rows") {
                "tenantId" to tenantId.key
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        // HTMX requests load the dialog into #dialog-host; a direct GET (no-JS,
        // deep link) still renders the full-page fallback.
        return request.htmx {
            pushUrl(urlWithCreateParam(request.htmxCurrentUrl, "/tenants/${tenantId.key}/environments"))
            fragment("environments/new", "createDialog") {
                "tenantId" to tenantId.key
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        val form = request.form {
            field("slug") {
                required()
                asEnvironmentId()
            }
            field("name") {
                required()
                maxLength(100)
            }
        }

        // Re-render on validation error: for HTMX the lone `createForm` fragment
        // swaps itself in place (hx-target="this"), keeping the dialog open with
        // field errors; for non-HTMX the full page is redrawn.
        fun reRender(
            formData: Map<String, String>,
            errors: Map<String, String>,
        ): ServerResponse = request.htmx {
            fragment("environments/new", "createForm") {
                "tenantId" to tenantId.key
                "formData" to formData
                "errors" to errors
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
        }

        if (form.hasErrors()) {
            return reRender(form.formData, form.errors)
        }

        val environmentKey = form.getEnvironmentId("slug")!!
        val name = form["name"]

        val result = form.executeOrFormError {
            CreateEnvironment(
                id = EnvironmentId(environmentKey, tenantId),
                name = name,
            ).execute()
        }

        if (result.hasErrors()) {
            return reRender(result.formData, result.errors)
        }

        val location = "/tenants/${tenantId.key}/environments"
        return if (request.isHtmx) {
            ServerResponse.ok().header("HX-Redirect", location).build()
        } else {
            ServerResponse.status(303).header("Location", location).build()
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val environmentId = request.environmentId(tenantId)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteEnvironment(id = environmentId).execute()
        } catch (e: EnvironmentInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // Return updated rows for HTMX
        val environments = ListEnvironments(tenantId = tenantId).query()
        return request.htmx {
            fragment("environments/list", "rows") {
                "tenantId" to tenantId.key
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
        }
    }
}
