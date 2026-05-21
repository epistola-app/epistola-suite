package app.epistola.suite.handlers

import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.tenants.commands.InvalidLocaleException
import app.epistola.suite.tenants.commands.SetTenantDefaultLocale
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Per-tenant default values (currently: default locale). Pattern mirrors
 * `FeatureHandler` — a single GET render and a plain form POST that 303s
 * back, no HTMX fragment plumbing.
 *
 * `GetTenant` only requires authentication, so this handler explicitly
 * gates on `TENANT_SETTINGS` before rendering. `UiExceptionFilter` turns
 * the thrown `PermissionDeniedException` into the standard 403 page.
 */
@Component
class DefaultsHandler(
    private val localeResolver: TenantLocaleResolver,
) {
    fun defaults(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val tenant = GetTenant(tenantId.key).query()
            ?: return ServerResponse.notFound().build()
        val entries = listBcp47Entries(tenantId.key)

        return ServerResponse.ok().page("defaults") {
            "pageTitle" to "Defaults - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "defaults"
            "tenant" to tenant
            "entries" to entries
            "effectiveLocale" to localeResolver.resolve(tenant)
            "applicationDefault" to localeResolver.applicationDefault
            "error" to null
        }
    }

    fun updateLocale(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TENANT_SETTINGS)
        val rawLocale = request.params().getFirst("locale")?.trim().orEmpty()
        val locale = rawLocale.ifBlank { null }

        try {
            SetTenantDefaultLocale(tenantId = tenantId.key, locale = locale).execute()
        } catch (e: InvalidLocaleException) {
            val tenant = GetTenant(tenantId.key).query()
                ?: return ServerResponse.notFound().build()
            val entries = listBcp47Entries(tenantId.key)
            return ServerResponse.ok().page("defaults") {
                "pageTitle" to "Defaults - Epistola"
                "tenantId" to tenantId.key
                "activeNavSection" to "defaults"
                "tenant" to tenant
                "entries" to entries
                "effectiveLocale" to localeResolver.resolve(tenant)
                "applicationDefault" to localeResolver.applicationDefault
                "error" to "Unknown locale '${e.locale}'. Pick a value from the list."
            }
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key.value}/defaults?saved=true")
            .build()
    }

    private fun listBcp47Entries(tenantKey: TenantKey) = ListCodeListEntries(
        codeListId = CodeListId(
            key = CodeListKey.of("bcp-47"),
            catalogId = CatalogId(key = SYSTEM_CATALOG_KEY, tenantId = TenantId(tenantKey)),
        ),
    ).query()
}
