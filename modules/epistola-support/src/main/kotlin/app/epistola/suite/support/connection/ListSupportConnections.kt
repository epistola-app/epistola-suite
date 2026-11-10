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

/** How a connection stands, for the platform page; a registering one also has its [SupportConnectionListing.state]. */
enum class SupportConnectionStanding {
    /** Not checked yet. */
    NOT_CHECKED,

    /** It accepts the token. */
    ACTIVE,

    /** It refused the token, or said the connection is not active. */
    REFUSED,
}

/**
 * A support connection as the platform page lists it (ADR 0053): every one, however it signs in.
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
    /** The connection it escalates to when it asks; null: none. */
    val escalatesTo: UUID? = null,
    /** How the suite signs in to it. */
    val auth: SupportConnectionAuth = SupportConnectionAuth.BEARER_TOKEN,
    /** Where it stands, as [SupportConnectionState]'s name (`Pending`, `Active`, …). */
    val state: String = "Unregistered",
) {
    /** It registers with the installation's own key: its registration is on a page of its own. */
    val registers: Boolean get() = auth == SupportConnectionAuth.PRIVATE_KEY_JWT
}

/** The installation's support connections, oldest first. No network I/O. */
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
    override fun handle(query: ListSupportConnections): List<SupportConnectionListing> = connections.all().mapNotNull { record ->
        val connection = registry.find(record.id) ?: return@mapNotNull null
        val status = connection.status()
        val state = status.state
        SupportConnectionListing(
            id = record.id,
            name = record.name,
            endpoint = record.endpoint,
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
            privacy = privacy.forConnection(record.id),
            escalatesTo = record.escalatesTo,
            auth = record.auth,
            state = state.javaClass.simpleName,
        )
    }
}
