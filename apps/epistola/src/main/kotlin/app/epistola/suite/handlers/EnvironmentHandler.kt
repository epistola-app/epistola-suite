package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class EnvironmentHandler {
    fun list(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val environments = ListEnvironments(tenantId = TenantId.of(tenantId)).query()
        return ServerResponse.ok().render(
            "environments/list",
            mapOf(
                "tenantId" to tenantId,
                "environments" to environments,
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val searchTerm = request.param("q").orElse(null)
        val environments = ListEnvironments(tenantId = TenantId.of(tenantId), searchTerm = searchTerm).query()
        return request.htmx {
            fragment("environments/list", "rows") {
                "tenantId" to tenantId
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/$tenantId/environments") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val slug = request.params().getFirst("slug")?.trim().orEmpty()
        val name = request.params().getFirst("name")?.trim().orEmpty()

        // Validate slug
        val environmentId = EnvironmentId.validateOrNull(slug)
        if (environmentId == null) {
            val formData = mapOf("slug" to slug, "name" to name)
            val errors = mapOf("slug" to "Invalid environment ID format. Must be 3-30 characters, start with a letter, and contain only lowercase letters, numbers, and hyphens.")
            return request.htmx {
                fragment("environments/list", "create-form") {
                    "tenantId" to tenantId
                    "formData" to formData
                    "errors" to errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/tenants/$tenantId/environments") }
            }
        }

        val command = try {
            CreateEnvironment(
                id = environmentId,
                tenantId = TenantId.of(tenantId),
                name = name,
            )
        } catch (e: ValidationException) {
            val formData = mapOf("slug" to slug, "name" to name)
            val errors = mapOf(e.field to e.message)
            return request.htmx {
                fragment("environments/list", "create-form") {
                    "tenantId" to tenantId
                    "formData" to formData
                    "errors" to errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/tenants/$tenantId/environments") }
            }
        }

        command.execute()

        val environments = ListEnvironments(tenantId = TenantId.of(tenantId)).query()
        return request.htmx {
            fragment("environments/list", "rows") {
                "tenantId" to tenantId
                "environments" to environments
            }
            trigger("environmentCreated")
            onNonHtmx { redirect("/tenants/$tenantId/environments") }
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val environmentIdStr = request.pathVariable("environmentId")
        val environmentId = EnvironmentId.validateOrNull(environmentIdStr)
            ?: return ServerResponse.badRequest().build()

        try {
            DeleteEnvironment(tenantId = TenantId.of(tenantId), id = environmentId).execute()
        } catch (e: EnvironmentInUseException) {
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }

        // Return updated rows for HTMX
        val environments = ListEnvironments(tenantId = TenantId.of(tenantId)).query()
        return request.htmx {
            fragment("environments/list", "rows") {
                "tenantId" to tenantId
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/$tenantId/environments") }
        }
    }
}
