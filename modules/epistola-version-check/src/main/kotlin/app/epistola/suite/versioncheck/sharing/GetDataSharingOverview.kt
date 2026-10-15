// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck.sharing

import app.epistola.suite.installation.InstallationService
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.versioncheck.CheckSummary
import app.epistola.suite.versioncheck.InstallationCheckService
import app.epistola.suite.versioncheck.ProductDiscoverySummary
import app.epistola.suite.versioncheck.SharingProperties
import app.epistola.suite.versioncheck.SupportBadge
import app.epistola.suite.versioncheck.SupportBadgeService
import app.epistola.suite.versioncheck.VersionCheckService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What this installation shares with Epistola: who it is, what it is set to share, each check as
 * the check itself reports it, and the badge that follows.
 */
data class DataSharingOverview(
    val installationId: UUID,
    val currentVersion: String,
    val sharing: SharingProperties,
    val versionCheck: CheckSummary,
    val installationCheck: CheckSummary,
    /** The discovery document both checks find their endpoints in (ADR 0030). */
    val discovery: CheckSummary,
    val badge: SupportBadge,
)

/** Reads the overview for `/platform/sharing`. Gated like the other platform pages. */
class GetDataSharingOverview :
    Query<DataSharingOverview>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER
}

@Component
class GetDataSharingOverviewHandler(
    private val installationService: InstallationService,
    private val sharing: SharingProperties,
    private val versionCheck: VersionCheckService,
    private val installationCheck: InstallationCheckService,
    private val badge: SupportBadgeService,
    private val discovery: ProductDiscoverySummary,
) : QueryHandler<GetDataSharingOverview, DataSharingOverview> {
    override fun handle(query: GetDataSharingOverview) = DataSharingOverview(
        installationId = installationService.get().id,
        currentVersion = installationCheck.currentVersion,
        sharing = sharing,
        versionCheck = versionCheck.summary(),
        installationCheck = installationCheck.summary(),
        discovery = discovery.summary(),
        badge = badge.badge(),
    )
}
