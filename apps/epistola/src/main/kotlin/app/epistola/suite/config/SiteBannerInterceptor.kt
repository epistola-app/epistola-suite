package app.epistola.suite.config

import app.epistola.suite.banner.queries.ResolveSiteBanner
import app.epistola.suite.mediator.query
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * Adds the active installation-wide site banner to shell pages as the `siteBanner`
 * model attribute, so it renders across the whole app (below the nav).
 *
 * [ResolveSiteBanner] is [app.epistola.suite.security.SystemInternal], so the banner
 * shows for any signed-in user regardless of permissions, and the read is served
 * from the short-TTL cache in `SiteBannerStore` — no DB hit on most renders. Uses
 * the request-bound `MediatorContext` via `.query()`, the same idiom as
 * `ShellModelInterceptor`.
 */
@Component
class SiteBannerInterceptor : HandlerInterceptor {

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        if (modelAndView == null || modelAndView.viewName != "layout/shell") return
        if (modelAndView.model["siteBanner"] != null) return
        val banner = ResolveSiteBanner().query() ?: return
        modelAndView.addObject("siteBanner", banner)
    }
}
