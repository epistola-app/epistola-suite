package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.validation.DuplicateIdException
import app.epistola.suite.validation.ValidationException
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
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "environments/list",
                "pageTitle" to "Environments - Epistola",
                "tenant" to tenant,
                "tenantId" to tenantId.value,
                "environments" to environments,
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val searchTerm = request.param("q").orElse(null)
        val environments = ListEnvironments(tenantId = tenantId, searchTerm = searchTerm).query()
        return request.htmx {
            fragment("environments/list", "rows") {
                "tenantId" to tenantId.value
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/${tenantId.value}/environments") }
        }
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "environments/new",
                "pageTitle" to "New Environment - Epistola",
                "tenantId" to tenantId.value,
            ),
        )
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val slug = request.params().getFirst("slug")?.trim().orEmpty()
        val name = request.params().getFirst("name")?.trim().orEmpty()

        fun renderFormWithErrors(errors: Map<String, String>): ServerResponse {
            val formData = mapOf("slug" to slug, "name" to name)
            return ServerResponse.ok().render(
                "layout/shell",
                mapOf(
                    "contentView" to "environments/new",
                    "pageTitle" to "New Environment - Epistola",
                    "tenantId" to tenantId.value,
                    "formData" to formData,
                    "errors" to errors,
                ),
            )
        }

        // Validate slug
        val environmentId = EnvironmentId.validateOrNull(slug)
        if (environmentId == null) {
            return renderFormWithErrors(
                mapOf("slug" to "Invalid environment ID format. Must be 3-30 characters, start with a letter, and contain only lowercase letters, numbers, and hyphens."),
            )
        }

        try {
            CreateEnvironment(
                id = environmentId,
                tenantId = tenantId,
                name = name,
            ).execute()
        } catch (e: ValidationException) {
            return renderFormWithErrors(mapOf(e.field to e.message))
        } catch (e: DuplicateIdException) {
            return renderFormWithErrors(mapOf("slug" to "An environment with this ID already exists"))
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.value}/environments")
            .build()
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val environmentIdStr = request.pathVariable("environmentId")
        val environmentId = EnvironmentId.validateOrNull(environmentIdStr)
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
                "tenantId" to tenantId.value
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/${tenantId.value}/environments") }
        }
    }
}
