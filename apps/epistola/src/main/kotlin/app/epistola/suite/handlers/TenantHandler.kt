package app.epistola.suite.tenants

import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.changelog.ChangelogService
import app.epistola.suite.changelog.GetChangelogAcknowledgment
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.features.queries.GetFeatureToggles
import app.epistola.suite.handlers.AuthContext
import app.epistola.suite.handlers.ChangelogRenderer
import app.epistola.suite.htmx.HxSwap
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.loadtest.queries.ListLoadTestRuns
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePermission
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.tenants.TenantProvisioningPort
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.commands.InvalidLocaleException
import app.epistola.suite.tenants.commands.SetTenantDefaultLocale
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.themes.queries.ListThemes
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class TenantHandler(
    private val tenantProvisioner: TenantProvisioningPort,
    private val changelogRenderer: ChangelogRenderer,
    private val changelogService: ChangelogService,
    private val buildProperties: BuildProperties?,
    private val localeResolver: TenantLocaleResolver,
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

        // Always show latest changelog entry; highlight if unseen
        val appVersion = buildProperties?.version ?: "dev"
        val allEntries = changelogRenderer.entries()
        val latestEntry = allEntries.firstOrNull()
        val principal = SecurityContext.current()
        val lastAcknowledged = GetChangelogAcknowledgment(principal.userId).query()
        val hasUnseenChanges = changelogService.hasUnseenEntries(allEntries, appVersion, lastAcknowledged)

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
                "changelogVersion" to latestEntry?.version,
                "changelogSummary" to latestEntry?.summary,
                "changelogIsNew" to hasUnseenChanges,
            ),
        )
    }

    fun search(request: ServerRequest): ServerResponse {
        val searchTerm = request.queryParam("q")
        val tenants = ListTenants(searchTerm = searchTerm).query()
        val auth = AuthContext.platformOnly(SecurityContext.current())
        return request.htmx {
            fragment("tenants/list", "rows") {
                "tenants" to tenants
                "auth" to auth
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
                "auth" to auth
            }
            trigger("tenantCreated")
            onNonHtmx { redirect("/") }
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = TenantKey.of(request.pathVariable("tenantId"))
        val auth = AuthContext.platformOnly(SecurityContext.current())

        DeleteTenant(id = tenantId).execute()

        val tenants = ListTenants().query()
        return request.htmx {
            fragment("tenants/list", "rows") {
                "tenants" to tenants
                "auth" to auth
            }
            onNonHtmx { redirect("/") }
        }
    }

    /**
     * Tenant-level settings page. Surfaces the default-locale override and the
     * per-tenant feature toggles in one place; future tenant-scoped settings
     * should hang off this same page.
     *
     * `GetTenant` only requires authentication, so we explicitly gate this
     * page on `TENANT_SETTINGS` — otherwise a non-manager could load the form
     * and only be denied on submit. `UiExceptionFilter` turns the thrown
     * `PermissionDeniedException` into a 403 with the standard error page.
     */
    fun settings(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val tenant = GetTenant(tenantId.key).query()
            ?: return ServerResponse.notFound().build()
        val entries = listBcp47Entries(tenantId.key)
        val features = featureRows(tenantId.key)
        return ServerResponse.ok().page("tenants/settings") {
            "pageTitle" to "${tenant.name} — Settings"
            "tenantId" to tenantId.key
            "tenant" to tenant
            "activeNavSection" to "settings"
            "entries" to entries
            "effectiveLocale" to localeResolver.resolve(tenant)
            "applicationDefault" to localeResolver.applicationDefault
            "errors" to emptyMap<String, String>()
            "features" to features
        }
    }

    fun updateLocale(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        // Fail-fast before form parsing. The downstream `SetTenantDefaultLocale`
        // command also declares `RequiresPermission(TENANT_SETTINGS)`, so this
        // is belt-and-braces — but it keeps the contract obvious at the handler.
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val rawLocale = request.params().getFirst("locale")?.trim().orEmpty()
        val locale = rawLocale.ifBlank { null }

        try {
            SetTenantDefaultLocale(tenantId = tenantId.key, locale = locale).execute()
        } catch (e: InvalidLocaleException) {
            val tenant = GetTenant(tenantId.key).query()
                ?: return ServerResponse.notFound().build()
            val entries = listBcp47Entries(tenantId.key)
            val errorMap: Map<String, String> = mapOf(
                "locale" to "Unknown locale '${e.locale}'. Pick a value from the list.",
            )
            return request.htmx {
                fragment("tenants/settings", "settings-form") {
                    "tenantId" to tenantId.key
                    "tenant" to tenant
                    "entries" to entries
                    "effectiveLocale" to localeResolver.resolve(tenant)
                    "applicationDefault" to localeResolver.applicationDefault
                    "errors" to errorMap
                }
                reswap(HxSwap.OUTER_HTML)
                onNonHtmx { redirect("/tenants/${tenantId.key.value}/settings") }
            }
        }

        val tenant = GetTenant(tenantId.key).query()!!
        val entries = listBcp47Entries(tenantId.key)
        return request.htmx {
            fragment("tenants/settings", "settings-form") {
                "tenantId" to tenantId.key
                "tenant" to tenant
                "entries" to entries
                "effectiveLocale" to localeResolver.resolve(tenant)
                "applicationDefault" to localeResolver.applicationDefault
                "errors" to emptyMap<String, String>()
            }
            reswap(HxSwap.OUTER_HTML)
            trigger("tenantSettingsUpdated")
            onNonHtmx { redirect("/tenants/${tenantId.key.value}/settings") }
        }
    }

    fun updateFeatures(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)

        KnownFeatures.all.forEach { featureKey ->
            val enabled = request.param(featureKey.value).isPresent
            SaveFeatureToggle(
                tenantKey = tenantId.key,
                featureKey = featureKey,
                enabled = enabled,
            ).execute()
        }

        val features = featureRows(tenantId.key)
        return request.htmx {
            fragment("tenants/settings", "features-form") {
                "tenantId" to tenantId.key
                "features" to features
            }
            reswap(HxSwap.OUTER_HTML)
            trigger("tenantSettingsUpdated")
            onNonHtmx { redirect("/tenants/${tenantId.key.value}/settings") }
        }
    }

    private fun listBcp47Entries(tenantKey: TenantKey) = ListCodeListEntries(
        codeListId = CodeListId(
            key = CodeListKey.of("bcp-47"),
            catalogId = CatalogId(key = SYSTEM_CATALOG_KEY, tenantId = TenantId(tenantKey)),
        ),
    ).query()

    private fun featureRows(tenantKey: TenantKey): List<Map<String, Any>> {
        val toggles = GetFeatureToggles(tenantKey).query()
        return toggles.map { (key, enabled) ->
            mapOf(
                "key" to key.value,
                "enabled" to enabled,
                "description" to (KnownFeatures.descriptions[key] ?: ""),
            )
        }
    }
}
