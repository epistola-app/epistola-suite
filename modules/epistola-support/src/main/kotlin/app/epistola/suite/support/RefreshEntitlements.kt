// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.hub.client.EntitlementView
import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.error.HubUnauthenticatedException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import app.epistola.hub.client.EntitlementEffect as ClientEntitlementEffect

/**
 * Pulls the installation's entitlement set from the hub and stores it as last-known-good.
 *
 * Modelled as a command (not a plain service call) so it rides the mediator: it gets the
 * `epistola.mediator.command.duration` metric, an `event_log` audit row per successful refresh, and
 * is ready for future distributed dispatch. [SystemInternal] because it runs from background contexts
 * (startup hook, refresh scheduler, revision trigger) with no principal; the user-initiated refresh
 * is gated on `TENANT_SETTINGS` in the UI handler before dispatch.
 *
 * Dispatch through [EntitlementSyncService], which binds the mediator scope and stays fail-open.
 */
class RefreshEntitlements :
    Command<Unit>,
    SystemInternal,
    // Pulls entitlements from the hub over HTTP mid-command.
    SelfManagedTransaction

@Component
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class RefreshEntitlementsHandler(
    private val client: EpistolaHubClient,
    private val store: EntitlementStore,
) : CommandHandler<RefreshEntitlements, Unit> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: RefreshEntitlements) {
        try {
            val set = client.getEntitlements()
            store.save(StoredEntitlements(set.entitlements.map { it.toStored() }, set.revision, EpistolaClock.instant()))
            log.debug("Refreshed entitlements from hub: {} entr(ies), revision {}", set.entitlements.size, set.revision)
        } catch (e: HubUnauthenticatedException) {
            // Not registered yet — an expected no-op, not a failure. The next refresh picks it up.
            log.debug("Not registered with the hub yet; skipping entitlement refresh: {}", e.message)
        }
        // Any other error (unreachable / transport / server) propagates: the mediator records
        // outcome=failure and writes no audit row, and EntitlementSyncService keeps last-known-good.
    }
}

private fun EntitlementView.toStored(): StoredEntitlement = StoredEntitlement(
    featureKey = featureKey,
    tenant = tenant,
    effect =
    when (effect) {
        ClientEntitlementEffect.ALLOW -> EntitlementEffect.ALLOW
        ClientEntitlementEffect.DENY -> EntitlementEffect.DENY
    },
    expiresAt = expiresAt,
)
