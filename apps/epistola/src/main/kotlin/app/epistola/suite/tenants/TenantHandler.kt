package app.epistola.suite.tenants

import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class TenantHandler(
    private val mediator: Mediator,
) {
    fun list(request: ServerRequest): ServerResponse {
        val tenants = mediator.query(ListTenants())
        return ServerResponse.ok().render("tenants/list", mapOf("tenants" to tenants))
    }

    fun search(request: ServerRequest): ServerResponse {
        val searchTerm = request.param("q").orElse(null)
        val tenants = mediator.query(ListTenants(searchTerm = searchTerm))
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
        mediator.send(command)

        val tenants = mediator.query(ListTenants())
        return request.htmx {
            fragment("tenants/list", "rows") {
                "tenants" to tenants
            }
            trigger("tenantCreated")
            onNonHtmx { redirect("/") }
        }
    }
}
