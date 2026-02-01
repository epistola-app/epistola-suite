package app.epistola.suite.tenants

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.validation.ValidationException
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
        val name = request.params().getFirst("name")?.trim().orEmpty()

        val command = try {
            CreateTenant(id = TenantId.generate(), name = name)
        } catch (e: ValidationException) {
            val formData = mapOf("name" to name)
            val errors = mapOf(e.field to e.message)
            return request.htmx {
                fragment("tenants/list", "create-form") {
                    "formData" to formData
                    "errors" to errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/") }
            }
        }

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
