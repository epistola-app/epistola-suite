// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.connection.SupportPause
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.privacy.SupportConnectionNotFoundException
import app.epistola.suite.support.sharing.GetSharingRestrictions
import app.epistola.suite.support.sharing.SharingRestriction
import app.epistola.suite.time.EpistolaClock
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID

/** Whether the installation's support connection takes feedback, as a tenant administrator is told. */
enum class SupportRequestSharingConnection {
    /** The support connection is off (`epistola.support.enabled=false`). */
    OFF,

    /** On, but not registered, or the registration was refused. */
    NOT_CONNECTED,

    /** Registered, waiting for Epistola to activate the installation for a customer. */
    PENDING,

    /** Registered, but an installation administrator has not selected the feedback service. */
    NOT_SELECTED,

    /** Activated, but Epistola has not granted the feedback service. */
    NO_SUPPORT_REQUEST_SERVICE,

    /** Granted, but Epistola paused it for now: what waits is sent once the pause ends. */
    PAUSED,

    /** Activated with the feedback service: consented feedback is sent. */
    READY,
}

/**
 * The tenant's "Feedback sharing" settings page: its consent and whether the installation can send
 * feedback at all.
 *
 * @property supportService the support service feedback goes to; null until resolved.
 * @property pause the pause while [connection] is [SupportRequestSharingConnection.PAUSED]; null otherwise.
 * @property unreachable the last contact with the support service failed: nothing is sent until it
 *   succeeds again (the gate of every driver, `SupportRequestSyncGate.canSendNow`).
 */
data class SupportRequestSharingSettings(
    val tenantKey: TenantKey,
    val consent: SupportRequestSyncConsent,
    val connection: SupportRequestSharingConnection,
    val supportService: URI?,
    val pause: SupportPause? = null,
    val unreachable: Boolean = false,
    /** The installation's default, which applies while [SupportRequestSyncConsent.followsDefault]. */
    val installationDefault: SupportRequestSharingDefault = SupportRequestSharingDefault.NONE,
    /** An installation administrator does not allow sharing feedback (ADR 0052 §5): consent cannot be given. */
    val notAllowed: Boolean = false,
) {
    /** The tenant's new feedback goes to Epistola support now. */
    val sendsSupportRequest: Boolean get() = consent.inForce && connection == SupportRequestSharingConnection.READY && !unreachable

    /** The tenant consented, but the installation cannot send feedback now; it is sent once it can. */
    val consentedButWaiting: Boolean get() = consent.inForce && !sendsSupportRequest

    /**
     * Consent can be given: it is not in force yet, **and** the installation can send feedback now.
     * Offering it before then would take a decision the page cannot show the consequence of: who
     * receives the feedback is only known once Epistola has activated the installation.
     */
    val canGrant: Boolean get() = !consent.inForce && connection == SupportRequestSharingConnection.READY && !notAllowed

    /** Consent can be withdrawn whenever it is in force, whatever the connection does. */
    val canWithdraw: Boolean get() = consent.inForce
}

/** Reads [SupportRequestSharingSettings]. No network I/O: the connection state is cached. */
data class GetSupportRequestSharingSettings(
    override val tenantKey: TenantKey,
    /** The connection the tenant would share support requests with. */
    val connectionId: UUID = SupportConnections.DISCOVERED_ID,
) : Query<SupportRequestSharingSettings>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE
}

@Component
class GetSupportRequestSharingSettingsHandler(
    private val registry: SupportConnectionRegistry,
) : QueryHandler<GetSupportRequestSharingSettings, SupportRequestSharingSettings> {
    override fun handle(query: GetSupportRequestSharingSettings): SupportRequestSharingSettings {
        val connection = registry.find(query.connectionId) ?: throw SupportConnectionNotFoundException(query.connectionId)
        val status = connection.status()
        val state = status.state
        val sharing = when (state) {
            SupportConnectionState.Disabled -> SupportRequestSharingConnection.OFF

            SupportConnectionState.Unregistered, is SupportConnectionState.Expired, is SupportConnectionState.Refused, is SupportConnectionState.Disconnected, is SupportConnectionState.Declined -> SupportRequestSharingConnection.NOT_CONNECTED

            is SupportConnectionState.Pending ->
                if (connection.isSelected(SupportScope.REQUESTS)) SupportRequestSharingConnection.PENDING else SupportRequestSharingConnection.NOT_SELECTED

            is SupportConnectionState.Active -> when {
                !connection.isSelected(SupportScope.REQUESTS) -> SupportRequestSharingConnection.NOT_SELECTED
                connection.hasScope(SupportScope.REQUESTS) -> SupportRequestSharingConnection.READY
                pausedNow(state) != null -> SupportRequestSharingConnection.PAUSED
                else -> SupportRequestSharingConnection.NO_SUPPORT_REQUEST_SERVICE
            }
        }
        return SupportRequestSharingSettings(
            tenantKey = query.tenantKey,
            consent = GetSupportRequestSyncConsent(query.tenantKey, query.connectionId).query(),
            connection = sharing,
            supportService = status.issuer,
            pause = (state as? SupportConnectionState.Active)?.let { pausedNow(it) }?.takeIf { sharing == SupportRequestSharingConnection.PAUSED },
            unreachable = status.lastError != null,
            installationDefault = GetSupportRequestSharingDefault(query.connectionId).query(),
            notAllowed = GetSharingRestrictions(query.connectionId).query().of(REQUESTS_SECTION) == SharingRestriction.NOT_ALLOWED,
        )
    }

    /** The pause in force that holds feedback back, or null. */
    private fun pausedNow(state: SupportConnectionState.Active): SupportPause? = state.pause?.takeIf { SupportScope.REQUESTS in state.scopes && SupportScope.REQUESTS in it.scopes && it.inForceAt(EpistolaClock.instant()) }
}
