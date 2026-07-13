package app.epistola.suite.handlers

import app.epistola.suite.banner.SiteBannerSeverity
import app.epistola.suite.banner.commands.ClearSiteBanner
import app.epistola.suite.banner.commands.SetSiteBanner
import app.epistola.suite.banner.queries.GetSiteBanner
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Admin UI for the installation-wide site banner. Both routes dispatch through
 * the mediator, so the platform `TENANT_MANAGER` gate on [GetSiteBanner] /
 * [SetSiteBanner] / [ClearSiteBanner] is enforced centrally (a non-manager
 * hitting the URL gets the standard 403 page).
 */
@Component
class SiteBannerHandler {
    fun edit(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val banner = GetSiteBanner().query()
        return ServerResponse.ok().page("site-banner") {
            "pageTitle" to "Site Banner - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "site-banner"
            "banner" to banner
            "severities" to SiteBannerSeverity.entries
        }
    }

    fun save(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        when (request.param("action").orElse("save")) {
            "clear" -> ClearSiteBanner().execute()
            else -> SetSiteBanner(
                message = request.param("message").orElse(""),
                severity = request.param("severity")
                    .map { runCatching { SiteBannerSeverity.valueOf(it) }.getOrDefault(SiteBannerSeverity.WARNING) }
                    .orElse(SiteBannerSeverity.WARNING),
                enabled = request.param("enabled").isPresent,
            ).execute()
        }
        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/site-banner?saved=true")
            .build()
    }
}
