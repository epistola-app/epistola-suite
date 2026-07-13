package app.epistola.suite.banner.commands

import app.epistola.suite.banner.SiteBanner
import app.epistola.suite.banner.SiteBannerStore
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

/**
 * Seeds [banner] only if no site banner is stored yet, returning true when it
 * wrote. [SystemInternal] so bootstrap code (e.g. the demo loader) can seed the
 * "data may be reset" warning without a user context; it never overwrites an
 * existing banner, so an admin's later edit or clear survives restarts.
 */
data class SeedSiteBannerIfAbsent(
    val banner: SiteBanner,
) : Command<Boolean>,
    SystemInternal

@Component
class SeedSiteBannerIfAbsentHandler(
    private val store: SiteBannerStore,
) : CommandHandler<SeedSiteBannerIfAbsent, Boolean> {
    override fun handle(command: SeedSiteBannerIfAbsent): Boolean = store.seedIfAbsent(command.banner)
}
