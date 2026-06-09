package app.epistola.suite.support

import app.epistola.hub.client.EntitlementView
import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.error.HubException
import app.epistola.hub.client.error.HubUnauthenticatedException
import app.epistola.hub.client.error.HubUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Instant
import app.epistola.hub.client.EntitlementEffect as ClientEntitlementEffect

/**
 * Pulls the installation's entitlement set from the hub and stores it as last-known-good. A
 * successful fetch (including an empty set) replaces the stored set; any failure keeps the previous
 * one (fail-open) so a transient hub outage never disables a gated feature. Not-yet-registered is a
 * quiet no-op — the periodic refresh picks it up once credentials exist.
 */
@Service
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class EntitlementSyncService(
    private val client: EpistolaHubClient,
    private val store: EntitlementStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun refresh() {
        try {
            val set = client.getEntitlements()
            store.save(StoredEntitlements(set.entitlements.map { it.toStored() }, set.revision, Instant.now()))
            log.debug("Refreshed entitlements from hub: {} entr(ies), revision {}", set.entitlements.size, set.revision)
        } catch (e: HubUnauthenticatedException) {
            log.debug("Not registered with the hub yet; skipping entitlement refresh: {}", e.message)
        } catch (e: HubUnavailableException) {
            log.warn("Hub unreachable; keeping last-known-good entitlements: {}", e.message)
        } catch (e: HubException) {
            log.warn("Hub could not serve entitlements; keeping last-known-good: {}", e.message)
        }
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
