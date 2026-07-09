package app.epistola.suite.versioncheck

import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.home.HomeNotice
import app.epistola.suite.htmx.home.HomeNoticeContributor
import org.springframework.stereotype.Component

/**
 * Contributes the tenant-home update / unsupported banner from the cached version-check status.
 * Emits a notice only when there is something to show — a newer applicable release or a running
 * version below the supported floor; otherwise nothing renders.
 */
@Component
class VersionCheckHomeNoticeContributor(
    private val service: VersionCheckService,
) : HomeNoticeContributor {
    override fun notices(context: UiRequestContext): List<HomeNotice> {
        val status = service.status() ?: return emptyList()
        if (status.supported && !status.updateAvailable) return emptyList()
        return listOf(HomeNotice(template = "versioncheck/home-notice", fragment = "banner", data = status))
    }
}
