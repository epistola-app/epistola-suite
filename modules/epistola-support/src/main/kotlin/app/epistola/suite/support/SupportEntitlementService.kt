package app.epistola.suite.support

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureEntitlementGate
import app.epistola.suite.features.KnownFeatures
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Instant

/** Effective entitlement decision for a feature + tenant, for display and gating. */
enum class EntitlementDecision {
    /** An ALLOW entry applies. */
    ALLOWED,

    /** A DENY entry applies (explicitly withheld). */
    DENIED,

    /** No entry configured — not entitled, and not shown to the customer. */
    NOT_CONFIGURED,
}

/**
 * Reads the hub-delivered entitlement set (last-known-good, from [EntitlementStore]) and resolves
 * whether a feature is entitled for a tenant. Mirrors the hub's resolution: a tenant-scoped entry
 * overrides an installation-wide one, and DENY beats ALLOW within the winning scope.
 *
 * Implements [FeatureEntitlementGate] so core's `ResolveAvailableFeatures` composes it with the local
 * toggle. Only constructed when `epistola.support.enabled=true`; OSS deployments have no bean, so
 * core treats every feature as ungated.
 */
@Service
@ConditionalOnSupportModule
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class SupportEntitlementService(
    private val store: EntitlementStore,
) : FeatureEntitlementGate {
    override val gatedFeatures: Set<FeatureKey> =
        setOf(KnownFeatures.SUPPORT_BACKUPS, KnownFeatures.SUPPORT_UPGRADING, KnownFeatures.SUPPORT_FEEDBACK)

    override fun isEntitled(
        featureKey: FeatureKey,
        tenantKey: TenantKey,
    ): Boolean = decision(featureKey, tenantKey) == EntitlementDecision.ALLOWED

    /** The effective decision for a feature + tenant, for richer display on the overview page. */
    fun decision(
        featureKey: FeatureKey,
        tenantKey: TenantKey,
    ): EntitlementDecision = resolveEntitlement(store.load()?.entries.orEmpty(), featureKey.value, tenantKey.value, Instant.now())

    /** All configured (non-expired) entries, for display. */
    fun entries(): List<StoredEntitlement> {
        val now = Instant.now()
        return store.load()?.entries?.filter { it.expiresAt == null || it.expiresAt.isAfter(now) } ?: emptyList()
    }

    /** When the entitlement set was last fetched from the hub, or null if never. */
    fun lastFetchedAt(): Instant? = store.load()?.fetchedAt
}

/**
 * Pure resolution of the effective entitlement decision, mirroring the hub: keep only non-expired
 * entries that match the feature and apply to the tenant (installation-wide or tenant-scoped); a
 * tenant-scoped entry overrides an installation-wide one; DENY beats ALLOW within the winning scope.
 * No matching entry → [EntitlementDecision.NOT_CONFIGURED].
 */
internal fun resolveEntitlement(
    entries: List<StoredEntitlement>,
    featureKey: String,
    tenant: String,
    now: Instant,
): EntitlementDecision {
    val matches =
        entries.filter {
            it.featureKey == featureKey &&
                (it.expiresAt == null || it.expiresAt.isAfter(now)) &&
                (it.tenant == null || it.tenant == tenant)
        }
    if (matches.isEmpty()) return EntitlementDecision.NOT_CONFIGURED
    val scoped = matches.filter { it.tenant == tenant }
    val winning = scoped.ifEmpty { matches }
    return if (winning.any { it.effect == EntitlementEffect.DENY }) {
        EntitlementDecision.DENIED
    } else {
        EntitlementDecision.ALLOWED
    }
}
