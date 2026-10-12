// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.installation.InstallationService
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Everything an operator needs to see what this installation shares with Epistola: the settings,
 * what each check sends (exactly), when it last ran and how, and the badge it leads to.
 */
data class DataSharingOverview(
    val installationId: UUID,
    val currentVersion: String,
    val sharing: SharingProperties,
    val versionCheckEnabled: Boolean,
    /** What the version check sends, as text: never anything that identifies the installation. */
    val versionCheckRequest: String,
    val versionCheckLastRun: Instant?,
    val versionCheckLastError: String?,
    /** What the installation check would send now, as text; null when the installation id is not shared. */
    val installationCheckRequest: String?,
    val installationCheck: InstallationCheckStatus?,
    /** The request the last installation check actually sent, as text. */
    val installationCheckLastRequest: String?,
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
    private val versionCheckProperties: VersionCheckProperties,
    private val versionCheck: VersionCheckService,
    private val installationCheck: InstallationCheckService,
    private val badge: SupportBadgeService,
) : QueryHandler<GetDataSharingOverview, DataSharingOverview> {
    override fun handle(query: GetDataSharingOverview): DataSharingOverview {
        val id = installationService.get().id
        val versionStatus = versionCheck.status()
        val installationStatus = installationCheck.status()
        return DataSharingOverview(
            installationId = id,
            currentVersion = installationCheck.currentVersion,
            sharing = sharing,
            versionCheckEnabled = versionCheckProperties.enabled,
            versionCheckRequest = describe(versionCheckProperties.wellKnownUrl, mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT)),
            versionCheckLastRun = versionStatus?.checkedAt,
            versionCheckLastError = versionStatus?.lastError,
            installationCheckRequest = if (sharing.installationId) installationCheck.request(id).let { (url, headers) -> describe(url, headers) } else null,
            installationCheck = installationStatus,
            installationCheckLastRequest = installationStatus?.lastRequest?.let { describe(it.url, it.headers) },
            badge = badge.badge(),
        )
    }
}

/** An HTTP request as an operator would read it: the request line, then one header per line. */
internal fun describe(url: String, headers: Map<String, String>): String = (listOf("GET $url") + headers.map { (name, value) -> "$name: $value" }).joinToString("\n")

/**
 * Runs the enabled checks now instead of waiting for tomorrow's window, e.g. after opening the
 * firewall. Sends exactly what the scheduled checks send.
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
) : CommandHandler<RunDataSharingChecks, Unit> {
    override fun handle(command: RunDataSharingChecks) {
        versionCheck.checkNow()
        installationCheck.checkNow()
    }
}
