// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
        // Nothing to show only for an up-to-date, supported, final (non pre-release) build; a
        // pre-release build always gets the informational banner acknowledging it.
        val nothingToShow = status.supported &&
            !status.preRelease &&
            !status.updateAvailable &&
            !status.supportEndingSoon
        if (nothingToShow) return emptyList()
        return listOf(HomeNotice(template = "versioncheck/home-notice", fragment = "banner", data = status))
    }
}
