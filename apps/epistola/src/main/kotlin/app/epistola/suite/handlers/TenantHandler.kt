package app.epistola.suite.tenants

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.redirect
import app.epistola.suite.loadtest.queries.ListLoadTestRuns
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.themes.queries.ListThemes
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class TenantHandler {
    fun list(request: ServerRequest): ServerResponse {
        val tenants = ListTenants().query()
        return ServerResponse.ok().render("tenants/list", mapOf("tenants" to tenants))
    }

    /**
     * Show tenant home page with navigation to templates, themes, load tests, etc.
     */
    fun home(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val tenant = GetTenant(tenantId).query()
            ?: return ServerResponse.notFound().build()

        // Get counts for each section
        val templateCount = ListDocumentTemplates(tenantId).query().size
        val themeCount = ListThemes(tenantId).query().size
        val loadTestCount = ListLoadTestRuns(tenantId, limit = 100).query().size
        val environmentCount = ListEnvironments(tenantId).query().size

        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "tenants/home",
                "pageTitle" to "${tenant.name} - Epistola",
                "tenantId" to tenantId.value,
                "tenant" to tenant,
                "templateCount" to templateCount,
                "themeCount" to themeCount,
                "loadTestCount" to loadTestCount,
                "environmentCount" to environmentCount,
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val searchTerm = request.queryParam("q")
        val tenants = ListTenants(searchTerm = searchTerm).query()
        return request.htmx {
            fragment("tenants/list", "rows") {
                "tenants" to tenants
            }
            onNonHtmx { redirect("/") }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val form = request.form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(20)
            }
            field("name") {
                required()
                maxLength(100)
            }
        }

        if (form.hasErrors()) {
            return request.htmx {
                fragment("tenants/list", "create-form") {
                    "formData" to form.formData
                    "errors" to form.errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/") }
            }
        }

        val tenantId = TenantId.of(form["slug"])
        val name = form["name"]

        val result = form.executeOrFormError {
            CreateTenant(id = tenantId, name = name).execute()
        }

        if (result.hasErrors()) {
            return request.htmx {
                fragment("tenants/list", "create-form") {
                    "formData" to result.formData
                    "errors" to result.errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/") }
            }
        }

        val tenants = ListTenants().query()
        return request.htmx {
            fragment("tenants/list", "rows") {
                "tenants" to tenants
            }
            trigger("tenantCreated")
            onNonHtmx { redirect("/") }
        }
    }
}
