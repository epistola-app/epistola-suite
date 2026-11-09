// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.SupportPrivacySettings
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** How a token connection stands, for the platform page. */
enum class SupportConnectionStanding {
    /** Not checked yet. */
    NOT_CHECKED,

    /** It accepts the token. */
    ACTIVE,

    /** It refused the token, or said the connection is not active. */
    REFUSED,
}

/**
 * A support connection other than the discovered one, as the platform page lists it (ADR 0053).
 *
 * @property granted the services it grants now; empty until a check found it active.
 * @property selected the services the installation uses over it, as far as they are granted.
 * @property lastError why the last check failed; null when it succeeded.
 * @property privacy what it learns of tenants and how much telemetry carries over it.
 */
data class SupportConnectionListing(
    val id: UUID,
    val name: String,
    val endpoint: String,
    val standing: SupportConnectionStanding,
    val reason: String?,
    val granted: List<String>,
    val selected: List<String>,
    val checkedAt: Instant?,
    val lastError: String?,
    val privacy: SupportPrivacySettings,
)

/** The installation's support connections besides Epistola support, oldest first. No network I/O. */
class ListSupportConnections :
    Query<List<SupportConnectionListing>>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class ListSupportConnectionsHandler(
    private val registry: SupportConnectionRegistry,
    private val connections: SupportConnections,
    private val privacy: SupportPrivacy,
) : QueryHandler<ListSupportConnections, List<SupportConnectionListing>> {
    override fun handle(query: ListSupportConnections): List<SupportConnectionListing> = registry.tokenConnections().map { connection ->
        val status = connection.status()
        val state = status.state
        SupportConnectionListing(
            id = connection.id,
            name = connections.find(connection.id)?.name ?: connection.endpoint.toString(),
            endpoint = connection.endpoint.toString(),
            standing = when (state) {
                is SupportConnectionState.Active -> SupportConnectionStanding.ACTIVE
                is SupportConnectionState.Refused -> SupportConnectionStanding.REFUSED
                else -> SupportConnectionStanding.NOT_CHECKED
            },
            reason = (state as? SupportConnectionState.Refused)?.reason,
            granted = (state as? SupportConnectionState.Active)?.scopes?.map { it.value }?.sorted().orEmpty(),
            selected = SupportScope.entries.filter { connection.isSelected(it) }.map { it.value },
            checkedAt = status.checkedAt,
            lastError = status.lastError,
            privacy = privacy.forConnection(connection.id),
        )
    }
}
