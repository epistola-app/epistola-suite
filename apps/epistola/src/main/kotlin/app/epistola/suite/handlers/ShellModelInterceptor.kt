package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
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
 * - `activeNavSection` — derived from the URL path (e.g., "templates", "themes")
 * - `tenantName` — resolved from the tenant ID in the model
 *
 * Only applies when the view is "layout/shell".
 */
@Component
class ShellModelInterceptor(
    private val featureToggleService: FeatureToggleService,
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

        // Feature toggles
        if (tenantId != null) {
            val tenantKey = TenantKey.of(tenantId)
            modelAndView.addObject(
                "feedbackEnabled",
                featureToggleService.isEnabled(tenantKey, KnownFeatures.FEEDBACK),
            )
        }

        // Shell-specific attributes
        if (viewName != "layout/shell") return
        val path = request.requestURI
        modelAndView.addObject("activeNavSection", resolveActiveSection(path))
    }

    private fun resolveActiveSection(path: String): String = when {
        "/templates" in path -> "templates"
        "/stencils" in path -> "stencils"
        "/themes" in path -> "themes"
        "/environments" in path -> "environments"
        "/attributes" in path -> "attributes"
        "/generation-history" in path -> "generation-history"
        "/load-tests" in path -> "load-tests"
        "/assets" in path -> "assets"
        "/settings" in path -> "settings"
        "/feedback" in path -> "feedback"
        "/features" in path -> "features"
        "/catalogs" in path -> "catalogs"
        "/admin" in path -> "admin"
        else -> "home"
    }
}
