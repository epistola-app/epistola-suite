package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.footer.FooterFragmentResolver
import app.epistola.suite.htmx.nav.NavMenuAggregator
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.tenants.queries.GetTenant
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * Populates common model attributes needed by the app shell layout (layout/shell)
 * on every tenant-scoped request.
 *
 * Adds:
 * - `navGroups` — the module-contributed nav model (see [NavMenuAggregator])
 * - `activeNavSection` — derived from the URL path (e.g., "templates", "themes")
 * - `tenantName` — resolved from the tenant ID in the model
 *
 * Only applies when the view is "layout/shell".
 */
@Component
class ShellModelInterceptor(
    private val navMenuAggregator: NavMenuAggregator,
    private val footerFragmentResolver: FooterFragmentResolver,
) : HandlerInterceptor {

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        if (modelAndView == null) return
        val viewName = modelAndView.viewName ?: return

        val tenantId = modelAndView.model["tenantId"]?.toString()

        // Resolve tenant name for any view that has a tenantId but no tenantName yet
        if (tenantId != null && modelAndView.model["tenantName"] == null) {
            val tenant = GetTenant(TenantKey.of(tenantId)).query()
            modelAndView.addObject("tenantName", tenant?.name ?: tenantId)
        }

        // Resolve auth context — always present so templates never need null checks.
        // If already set by a handler (e.g., TenantHandler.list), don't override.
        if (modelAndView.model["auth"] == null) {
            val principal = SecurityContext.currentOrNull()
            val auth = if (principal != null && tenantId != null) {
                AuthContext.from(principal, TenantKey.of(tenantId))
            } else if (principal != null) {
                AuthContext.platformOnly(principal)
            } else {
                AuthContext.NONE
            }
            modelAndView.addObject("auth", auth)
        }
        val auth = modelAndView.model["auth"] as AuthContext
        modelAndView.addObject("isManager", auth.has(Permission.TENANT_SETTINGS))

        if (tenantId == null) return
        val tenantKey = TenantKey.of(tenantId)
        val context = UiRequestContext(tenantKey) { auth.has(it) }

        // Editor feature toggle (gates editor UI, not nav/footer). Read through the internal query
        // like everything else; the per-request cache (FeatureToggleCacheFilter) shares one toggle
        // query across this and the nav/footer contributors.
        val toggles = ResolveFeatureToggles(tenantKey).query()
        modelAndView.addObject("stencilParametersEnabled", toggles[KnownFeatures.STENCIL_PARAMETERS] == true)

        // Module-contributed footer chrome (see FooterContributor / FooterFragmentResolver) — global
        // injections like the feedback FAB, rendered by the shell footer and the standalone editor
        // page alike, so it's resolved for any tenant view (not just the shell).
        modelAndView.addObject("footerFragments", footerFragmentResolver.resolve(context))

        // The module-contributed nav menu is shell-only; contributors resolve their own visibility,
        // so the host owns no feature flags for it.
        if (viewName != "layout/shell") return
        val nav = navMenuAggregator.build(context, request.requestURI)
        modelAndView.addObject("navGroups", nav.groups)
        modelAndView.addObject("activeNavSection", nav.activeNavSection)
    }
}
