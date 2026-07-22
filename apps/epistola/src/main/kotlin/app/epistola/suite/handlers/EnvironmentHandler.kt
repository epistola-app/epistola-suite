package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.environmentId
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
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
        return ServerResponse.ok().page("environments/list") {
            "pageTitle" to "Environments - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "environments" to environments
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
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("environments/new", "dialog") {
                "tenantId" to tenantId.key
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onNonHtmx {
                page("environments/list") {
                    listModel(tenantId)
                    "openDialog" to true
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)

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

        // Field validation and the command-level failure (duplicate slug) both
        // land as `errors` on the FormData, so they share one error path.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                CreateEnvironment(
                    id = EnvironmentId(form.getEnvironmentId("slug")!!, tenantId),
                    name = form["name"],
                ).execute()
            }
        }

        if (result.hasErrors()) {
            return request.htmx {
                // Re-render the form inside the dialog (retargeted to the form,
                // not the list) with inline errors + preserved values. `tenantId`
                // is prefill the form needs for its th:hx-post URL.
                dialogFieldErrors(
                    template = "environments/new",
                    fragmentName = "environment-form",
                    formTarget = "#create-environment-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                }
                onNonHtmx {
                    page(422, "environments/list") {
                        listModel(tenantId)
                        "openDialog" to true
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        val environments = ListEnvironments(tenantId = tenantId).query()
        return request.htmx {
            // Success: close the dialog + refresh the list out-of-band. Global
            // attributes the list fragment needs (`auth` for permission checks)
            // are injected by HtmxFragmentModelContributor on the OOB render path.
            dialogSuccess("environments/list", "environment-list", "/tenants/${tenantId.key}/environments") {
                "tenantId" to tenantId.key
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
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

        // Refresh the whole list region so the last delete flips to the empty
        // state (the empty-state lives in `environment-list`, outside the rows
        // tbody). Direct targeted swap into #environment-list (outerHTML), not
        // an OOB swap, so `oob` stays unset and hx-swap-oob renders null.
        val environments = ListEnvironments(tenantId = tenantId).query()
        return request.htmx {
            fragment("environments/list", "environment-list") {
                "tenantId" to tenantId.key
                "environments" to environments
            }
            onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
        }
    }

    /** The full-page list model (shared by the newForm / create non-HTMX branches). */
    private fun ModelBuilder.listModel(tenantId: TenantId) {
        "pageTitle" to "Environments - Epistola"
        "tenant" to GetTenant(tenantId.key).query()
        "tenantId" to tenantId.key
        "environments" to ListEnvironments(tenantId = tenantId).query()
    }
}
