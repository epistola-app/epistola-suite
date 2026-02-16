package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.query
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
class ShellModelInterceptor : HandlerInterceptor {

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
            val tenant = GetTenant(TenantId.of(tenantId)).query()
            modelAndView.addObject("tenantName", tenant?.name ?: tenantId)
        }

        // Shell-specific attributes
        if (viewName != "layout/shell") return
        val path = request.requestURI
        modelAndView.addObject("activeNavSection", resolveActiveSection(path))
    }

    private fun resolveActiveSection(path: String): String = when {
        "/templates" in path -> "templates"
        "/themes" in path -> "themes"
        "/environments" in path -> "environments"
        "/load-tests" in path -> "load-tests"
        else -> "home"
    }
}
