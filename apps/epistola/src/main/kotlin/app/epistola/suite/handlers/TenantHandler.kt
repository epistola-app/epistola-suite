package app.epistola.suite.tenants

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.handlers.AuthContext
import app.epistola.suite.handlers.ChangelogRenderer
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.loadtest.queries.ListLoadTestRuns
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.tenants.TenantProvisioningPort
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.themes.queries.ListThemes
import app.epistola.suite.users.queries.GetChangelogAcknowledgment
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class TenantHandler(
    private val tenantProvisioner: TenantProvisioningPort,
    private val changelogRenderer: ChangelogRenderer,
    private val buildProperties: BuildProperties?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    fun list(request: ServerRequest): ServerResponse {
        val tenants = ListTenants().query()
        val auth = AuthContext.platformOnly(SecurityContext.current())
        return ServerResponse.ok().render("tenants/list", mapOf("tenants" to tenants, "auth" to auth))
    }

    /**
     * Show tenant home page with navigation to templates, themes, load tests, etc.
     */
    fun home(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val tenant = GetTenant(tenantId.key).query()
            ?: return ServerResponse.notFound().build()

        // Get counts for each section
        val templateCount = ListDocumentTemplates(tenantId).query().size
        val themeCount = ListThemes(tenantId).query().size
        val loadTestCount = ListLoadTestRuns(tenantId.key, limit = 100).query().size
        val environmentCount = ListEnvironments(tenantId).query().size

        // Determine if there are unseen changelog entries
        val appVersion = buildProperties?.version ?: "dev"
        val principal = SecurityContext.current()
        val lastAcknowledged = GetChangelogAcknowledgment(principal.userId).query()
        val changelogEntries = if (appVersion != "dev" && lastAcknowledged != appVersion) {
            changelogRenderer.entriesSince(lastAcknowledged).ifEmpty { null }
        } else {
            null
        }

        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "tenants/home",
                "pageTitle" to "${tenant.name} - Epistola",
                "tenantId" to tenantId.key,
                "tenant" to tenant,
                "templateCount" to templateCount,
                "themeCount" to themeCount,
                "loadTestCount" to loadTestCount,
                "environmentCount" to environmentCount,
                "changelogEntries" to changelogEntries,
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
        val auth = AuthContext.platformOnly(SecurityContext.current())
        val form = request.form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(63)
            }
            field("name") {
                required()
                maxLength(100)
            }
        }

        if (form.hasErrors()) {
            return request.htmx {
                fragment("tenants/list", "create-form") {
                    "auth" to auth
                    "formData" to form.formData
                    "errors" to form.errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/") }
            }
        }

        val tenantId = TenantKey.validateOrNull(form["slug"])
        if (tenantId == null) {
            val errors = mapOf("slug" to "Invalid tenant ID format")
            return request.htmx {
                fragment("tenants/list", "create-form") {
                    "auth" to auth
                    "formData" to form.formData
                    "errors" to errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/") }
            }
        }

        val name = form["name"]

        val result = form.executeOrFormError {
            CreateTenant(id = tenantId, name = name).execute()
        }

        if (result.hasErrors()) {
            return request.htmx {
                fragment("tenants/list", "create-form") {
                    "auth" to auth
                    "formData" to result.formData
                    "errors" to result.errors
                }
                retarget("#create-form")
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/") }
            }
        }

        // Provision IDP resources (non-critical — log warning on failure)
        try {
            tenantProvisioner.provisionTenant(tenantId, name)
        } catch (e: Exception) {
            log.warn("Tenant '{}' created but IDP provisioning failed: {}", tenantId.value, e.message)
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
