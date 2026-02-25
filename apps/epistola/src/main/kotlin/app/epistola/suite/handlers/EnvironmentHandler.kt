package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.pathId
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.redirect
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
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val tenant = GetTenant(tenantId).query() ?: return ServerResponse.notFound().build()
        val environments = ListEnvironments(tenantId = tenantId).query()
        return ServerResponse.ok().page("environments/list") {
            "pageTitle" to "Environments - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId
            "environments" to environments
        }
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val searchTerm = request.queryParam("q")
        val environments = ListEnvironments(tenantId = tenantId, searchTerm = searchTerm).query()
        return request.htmx {
            fragment("environments/list", "rows") {
                "tenantId" to tenantId
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/$tenantId/environments") }
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        return ServerResponse.ok().page("environments/new") {
            "pageTitle" to "New Environment - Epistola"
            "tenantId" to tenantId
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))

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

        if (form.hasErrors()) {
            return ServerResponse.ok().page("environments/new") {
                "pageTitle" to "New Environment - Epistola"
                "tenantId" to tenantId
                "formData" to form.formData
                "errors" to form.errors
            }
        }

        val environmentId = form.getEnvironmentId("slug")!!
        val name = form["name"]

        val result = form.executeOrFormError {
            CreateEnvironment(
                id = environmentId,
                tenantId = tenantId,
                name = name,
            ).execute()
        }

        if (result.hasErrors()) {
            return ServerResponse.ok().page("environments/new") {
                "pageTitle" to "New Environment - Epistola"
                "tenantId" to tenantId
                "formData" to result.formData
                "errors" to result.errors
            }
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/$tenantId/environments")
            .build()
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val environmentId = request.pathId("environmentId") { EnvironmentId.validateOrNull(it) }
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteEnvironment(tenantId = tenantId, id = environmentId).execute()
        } catch (e: EnvironmentInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // Return updated rows for HTMX
        val environments = ListEnvironments(tenantId = tenantId).query()
        return request.htmx {
            fragment("environments/list", "rows") {
                "tenantId" to tenantId
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/$tenantId/environments") }
        }
    }
}
