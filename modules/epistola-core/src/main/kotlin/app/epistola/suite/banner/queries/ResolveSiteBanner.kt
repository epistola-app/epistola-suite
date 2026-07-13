package app.epistola.suite.banner.queries

import app.epistola.suite.banner.SiteBanner
import app.epistola.suite.banner.SiteBannerStore
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

/**
 * Resolves the **active** site banner for rendering in the app shell to any
 * signed-in user. Returns null when no banner is set or it is disabled.
 *
 * It is [SystemInternal] (bypasses authorization) because the banner must render
 * regardless of the viewer's permissions — same rationale as `ResolveFeatureToggles`.
 * The permission-gated sibling that backs the admin edit page is [GetSiteBanner].
 */
class ResolveSiteBanner :
    Query<SiteBanner?>,
    SystemInternal

@Component
class ResolveSiteBannerHandler(
    private val store: SiteBannerStore,
) : QueryHandler<ResolveSiteBanner, SiteBanner?> {
    override fun handle(query: ResolveSiteBanner): SiteBanner? = store.get()?.takeIf { it.enabled }
}
