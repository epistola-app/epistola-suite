package app.epistola.suite.tenants

import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.CreateTenantHandler
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.tenants.queries.ListTenantsHandler
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class TenantHandler(
    private val listHandler: ListTenantsHandler,
    private val createHandler: CreateTenantHandler,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenants = listHandler.handle(ListTenants())
        return ServerResponse.ok().render("tenants/list", mapOf("tenants" to tenants))
    }

    fun search(request: ServerRequest): ServerResponse {
        val searchTerm = request.param("q").orElse(null)
        val tenants = listHandler.handle(ListTenants(searchTerm = searchTerm))
        return request.htmx {
            fragment("tenants/list", "rows") {
                "tenants" to tenants
            }
            onNonHtmx { redirect("/") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val formData = request.params()
        val command = CreateTenant(
            name = formData.getFirst("name") ?: throw IllegalArgumentException("Name is required"),
        )
        createHandler.handle(command)

        val tenants = listHandler.handle(ListTenants())
        return request.htmx {
            fragment("tenants/list", "rows") {
                "tenants" to tenants
            }
            trigger("tenantCreated")
            onNonHtmx { redirect("/") }
        }
    }
}
