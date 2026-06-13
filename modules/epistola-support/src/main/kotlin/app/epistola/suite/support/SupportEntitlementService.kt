package app.epistola.suite.support

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureEntitlementGate
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.time.EpistolaClock
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

    /**
     * Whether the installation holds an installation-wide (`tenant == null`) ALLOW for [featureKey],
     * ignoring per-tenant entries. Gates installation-scoped capabilities that have no per-tenant
     * toggle (e.g. the telemetry leg — ADR 0006): for such a feature a tenant-scoped entry only ever
     * withholds a single tenant's data (see [deniedTenants]).
     */
    fun isInstallationEntitled(featureKey: FeatureKey): Boolean = resolveInstallationEntitlement(store.load()?.entries.orEmpty(), featureKey.value, EpistolaClock.instant())

    /**
     * The tenants explicitly DENY'd for [featureKey] by a non-expired tenant-scoped entry — the
     * per-tenant opt-out for an otherwise installation-wide capability.
     */
    fun deniedTenants(featureKey: FeatureKey): Set<String> {
        val now = EpistolaClock.instant()
        return store
            .load()
            ?.entries
            .orEmpty()
            .filter {
                it.featureKey == featureKey.value &&
                    it.tenant != null &&
                    it.effect == EntitlementEffect.DENY &&
                    (it.expiresAt == null || it.expiresAt.isAfter(now))
            }.mapNotNull { it.tenant }
            .toSet()
    }

    /** The effective decision for a feature + tenant, for richer display on the overview page. */
    fun decision(
        featureKey: FeatureKey,
        tenantKey: TenantKey,
    ): EntitlementDecision = resolveEntitlement(store.load()?.entries.orEmpty(), featureKey.value, tenantKey.value, EpistolaClock.instant())

    /** All configured (non-expired) entries, for display. */
    fun entries(): List<StoredEntitlement> {
        val now = EpistolaClock.instant()
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

/**
 * Pure installation-wide resolution: true when at least one non-expired installation-wide
 * (`tenant == null`) entry for [featureKey] exists and none of them is a DENY (DENY beats ALLOW
 * within the scope). Per-tenant entries are ignored — they refine an installation-wide grant, they
 * never create one.
 */
internal fun resolveInstallationEntitlement(
    entries: List<StoredEntitlement>,
    featureKey: String,
    now: Instant,
): Boolean {
    val installWide =
        entries.filter {
            it.featureKey == featureKey &&
                it.tenant == null &&
                (it.expiresAt == null || it.expiresAt.isAfter(now))
        }
    return installWide.isNotEmpty() && installWide.none { it.effect == EntitlementEffect.DENY }
}
