package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.footer.FooterFragmentResolver
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component

/**
 * The one source of truth for the common tenant-view model attributes that every
 * tenant-scoped UI render needs — `tenantName`, `auth`, `isManager`, and footer
 * chrome.
 *
 * Two render paths consume it, so they can't drift:
 * - [ShellModelInterceptor] — normal MVC view renders (interceptor `postHandle`).
 * - [HtmxFragmentModelContributor] — the HTMX multi-fragment / OOB render path,
 *   which renders through the template engine directly and therefore does NOT
 *   run interceptors.
 *
 * Excludes the shell-only nav (`navGroups`/`activeNavSection`) — fragments are
 * not the shell; the interceptor adds those itself.
 */
@Component
class CommonViewModel(
    private val footerFragmentResolver: FooterFragmentResolver,
) {
    /**
     * The attributes to add for a tenant view, computed from [model]:
     * - `tenantName` — resolved from `tenantId` (only if not already in [model]).
     * - `auth` — resolved from the current principal + `tenantId` (only if not
     *   already in [model], so a handler that set its own `auth` keeps it).
     * - `isManager` — derived from the effective `auth`.
     * - `footerFragments` — only when `tenantId` is present.
     *
     * Callers must apply these WITHOUT overriding keys already set in [model]
     * (the returned map already skips `tenantName`/`auth` when the caller set
     * them; the merge should still let caller keys win for the rest).
     */
    fun attributes(model: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val tenantId = model["tenantId"]?.toString()

        if (tenantId != null && model["tenantName"] == null) {
            val tenant = GetTenant(TenantKey.of(tenantId)).query()
            result["tenantName"] = tenant?.name ?: tenantId
        }

        // Resolve auth context — always present so templates never need null checks.
        val auth = model["auth"] as? AuthContext ?: run {
            val principal = SecurityContext.currentOrNull()
            when {
                principal != null && tenantId != null -> AuthContext.from(principal, TenantKey.of(tenantId))
                principal != null -> AuthContext.platformOnly(principal)
                else -> AuthContext.NONE
            }
        }
        if (model["auth"] == null) result["auth"] = auth
        result["isManager"] = auth.has(Permission.TENANT_SETTINGS)

        if (tenantId != null) {
            val context = UiRequestContext(TenantKey.of(tenantId)) { auth.has(it) }
            // Module-contributed footer chrome (e.g. the feedback FAB).
            result["footerFragments"] = footerFragmentResolver.resolve(context)
        }
        return result
    }
}
