// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.home.HomeNotice
import app.epistola.suite.htmx.home.HomeNoticeContributor
import org.springframework.stereotype.Component

/**
 * Contributes the tenant-home notices from the cached version-check status: first a security notice
 * when published advisories apply to the running version, then the update / unsupported banner when
 * there is a newer applicable release or the running version is below the supported floor.
 */
@Component
class VersionCheckHomeNoticeContributor(
    private val service: VersionCheckService,
) : HomeNoticeContributor {
    override fun notices(context: UiRequestContext): List<HomeNotice> {
        val status = service.status() ?: return emptyList()
        val advisory = if (status.advisories.isNullOrEmpty()) {
            emptyList()
        } else {
            listOf(HomeNotice(template = "versioncheck/home-notice", fragment = "advisories", data = status))
        }
        // Nothing to show only for an up-to-date, supported, final (non pre-release) build; a
        // pre-release build always gets the informational banner acknowledging it.
        val nothingToShow = status.supported &&
            !status.preRelease &&
            !status.updateAvailable &&
            !status.supportEndingSoon
        if (nothingToShow) return advisory
        return advisory + HomeNotice(template = "versioncheck/home-notice", fragment = "banner", data = status)
    }
}
