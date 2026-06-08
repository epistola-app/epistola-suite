package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.footer.FooterFragmentResolver
import app.epistola.suite.htmx.nav.NavMenuAggregator
import app.epistola.suite.mediator.query
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
    private val featureToggleService: FeatureToggleService,
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
        modelAndView.addObject("isManager", auth.has("TENANT_SETTINGS"))

        // Editor feature toggle (gates editor UI, not nav/footer).
        if (tenantId != null) {
            modelAndView.addObject(
                "stencilParametersEnabled",
                featureToggleService.isEnabled(TenantKey.of(tenantId), KnownFeatures.STENCIL_PARAMETERS),
            )
        }

        // Shell-specific attributes: the module-contributed nav menu + footer chrome. Both are
        // contributed by modules (see NavMenuAggregator / FooterFragmentResolver), so the host
        // owns no feature flags for them.
        if (viewName != "layout/shell" || tenantId == null) return
        val tenantKey = TenantKey.of(tenantId)
        val nav = navMenuAggregator.build(tenantKey, request.requestURI, auth::has)
        modelAndView.addObject("navGroups", nav.groups)
        modelAndView.addObject("activeNavSection", nav.activeNavSection)
        modelAndView.addObject("footerFragments", footerFragmentResolver.resolve(tenantKey, auth::has))
    }
}
