package app.epistola.suite.handlers

import app.epistola.suite.banner.SiteBannerSeverity
import app.epistola.suite.banner.commands.ClearSiteBanner
import app.epistola.suite.banner.commands.SetSiteBanner
import app.epistola.suite.banner.queries.GetSiteBanner
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Admin UI for the installation-wide site banner. It is a platform-level page (no
 * tenant in the URL — the banner spans all tenants) reachable from the tenant list
 * at `/`. Both routes dispatch through the mediator, so the platform `TENANT_MANAGER`
 * gate on [GetSiteBanner] / [SetSiteBanner] / [ClearSiteBanner] is enforced centrally
 * (a non-manager hitting the URL gets the standard 403 page).
 */
@Component
class SiteBannerHandler {
    fun edit(request: ServerRequest): ServerResponse {
        val banner = GetSiteBanner().query()
        return ServerResponse.ok().render(
            "platform/banner",
            mapOf(
                "pageTitle" to "Site Banner - Epistola",
                "banner" to banner,
                "severities" to SiteBannerSeverity.entries,
            ),
        )
    }

    fun save(request: ServerRequest): ServerResponse {
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
            .header("Location", "/platform/banner?saved=true")
            .build()
    }
}
