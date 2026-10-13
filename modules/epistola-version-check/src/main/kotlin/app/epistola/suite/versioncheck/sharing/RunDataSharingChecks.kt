// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck.sharing

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.versioncheck.InstallationCheckService
import app.epistola.suite.versioncheck.ProductDiscoverySummary
import app.epistola.suite.versioncheck.VersionCheckService
import org.springframework.stereotype.Component

/**
 * Runs the enabled checks now instead of waiting for tomorrow's window, e.g. after opening the
 * firewall. Sends exactly what the scheduled checks send; a check that is off does nothing. The
 * discovery document is fetched first when a check relies on it, so a moved endpoint is picked up.
 */
class RunDataSharingChecks :
    Command<Unit>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER
}

@Component
class RunDataSharingChecksHandler(
    private val versionCheck: VersionCheckService,
    private val installationCheck: InstallationCheckService,
    private val discovery: ProductDiscoverySummary,
) : CommandHandler<RunDataSharingChecks, Unit> {
    override fun handle(command: RunDataSharingChecks) {
        discovery.refreshIfInUse()
        versionCheck.checkNow()
        installationCheck.checkNow()
    }
}
