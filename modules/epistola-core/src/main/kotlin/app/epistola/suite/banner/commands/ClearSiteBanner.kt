package app.epistola.suite.banner.commands

import app.epistola.suite.banner.SiteBanner
import app.epistola.suite.banner.SiteBannerSeverity
import app.epistola.suite.banner.SiteBannerStore
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import org.springframework.stereotype.Component

/**
 * Clears the site banner. Gated to the platform [PlatformRole.TENANT_MANAGER].
 *
 * It writes a **disabled** banner rather than deleting the row, so the demo
 * bootstrap's `setIfAbsent` seed (see `SeedSiteBannerIfAbsent`) does not re-add
 * the demo warning after an admin has cleared it.
 */
class ClearSiteBanner :
    Command<Unit>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER
}

@Component
class ClearSiteBannerHandler(
    private val store: SiteBannerStore,
) : CommandHandler<ClearSiteBanner, Unit> {
    override fun handle(command: ClearSiteBanner) {
        store.set(SiteBanner(message = "", severity = SiteBannerSeverity.INFO, enabled = false))
    }
}
