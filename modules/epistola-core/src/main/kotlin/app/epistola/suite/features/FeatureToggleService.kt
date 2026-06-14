package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Resolves per-tenant feature toggles (DB override, falling back to [FeatureDefaults]).
 *
 * `isEnabled` runs many times per render (nav + footer contributors, editor flags), so reads are
 * memoized for the duration of a [withRequestCache] scope: the first lookup for a tenant loads all
 * of its toggles in one query and caches them, so a whole page render issues a single toggle query
 * per tenant. The scope is bound per HTTP request by `FeatureToggleCacheFilter`; outside any scope
 * (e.g. background schedulers) each call reads straight from the DB — no cross-call caching, no
 * staleness. This uses [ScopedValue] for the same reasons as [app.epistola.suite.security.SecurityContext]
 * / [app.epistola.suite.mediator.MediatorContext]: virtual-thread friendly, auto-unbound at scope end.
 *
 * Per-request scope is safe because the app never mutates a toggle and re-reads it within the same
 * request: the Features page saves then 303-redirects to a fresh GET.
 */
@Component
@EnableConfigurationProperties(FeatureDefaults::class)
class FeatureToggleService(
    private val jdbi: Jdbi,
    private val defaults: FeatureDefaults,
    // Hub-only support features (backups/upgrading) default to the support tier: on when it's enabled,
    // off otherwise (OSS). Feedback is freely usable, so it keeps its FeatureDefaults default.
    @Value("\${epistola.support.enabled:false}") private val supportEnabled: Boolean,
) {
    private val requestCache: ScopedValue<MutableMap<TenantKey, Map<FeatureKey, Boolean>>> = ScopedValue.newInstance()

    /** Binds an empty per-request toggle cache for the duration of [block] (see class KDoc). */
    fun <T> withRequestCache(block: () -> T): T = ScopedValue.where(requestCache, HashMap<TenantKey, Map<FeatureKey, Boolean>>()).call<T, RuntimeException>(block)

    fun isEnabled(tenantKey: TenantKey, featureKey: FeatureKey): Boolean = resolveAll(tenantKey)[featureKey] ?: defaultFor(featureKey)

    /** Global default for a feature with no tenant override: hub-only features follow the tier, else [FeatureDefaults]. */
    internal fun defaultFor(featureKey: FeatureKey): Boolean = if (featureKey in KnownFeatures.HUB_ONLY) supportEnabled else defaults.isEnabled(featureKey)

    fun resolveAll(tenantKey: TenantKey): Map<FeatureKey, Boolean> {
        if (!requestCache.isBound) return loadAll(tenantKey)
        val cache = requestCache.get()
        return cache[tenantKey] ?: loadAll(tenantKey).also { cache[tenantKey] = it }
    }

    private fun loadAll(tenantKey: TenantKey): Map<FeatureKey, Boolean> {
        val overrides = jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                "SELECT feature_key, enabled FROM feature_toggles WHERE tenant_key = :tenantKey",
            )
                .bind("tenantKey", tenantKey)
                .map { rs, _ -> FeatureKey.of(rs.getString("feature_key")) to rs.getBoolean("enabled") }
                .list()
                .toMap()
        }
        return KnownFeatures.all.associateWith { feature ->
            overrides[feature] ?: defaultFor(feature)
        }
    }
}
