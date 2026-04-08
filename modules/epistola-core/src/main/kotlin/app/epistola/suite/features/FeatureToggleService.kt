package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component

// TODO: Add Caffeine/Spring Cache with short TTL (~60s) — isEnabled() runs on every page load via ShellModelInterceptor
@Component
@EnableConfigurationProperties(FeatureDefaults::class)
class FeatureToggleService(
    private val jdbi: Jdbi,
    private val defaults: FeatureDefaults,
) {
    fun isEnabled(tenantKey: TenantKey, featureKey: FeatureKey): Boolean {
        val override = jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                "SELECT enabled FROM feature_toggles WHERE tenant_key = :tenantKey AND feature_key = :featureKey",
            )
                .bind("tenantKey", tenantKey)
                .bind("featureKey", featureKey)
                .mapTo(Boolean::class.java)
                .findOne()
                .orElse(null)
        }
        return override ?: defaults.isEnabled(featureKey)
    }

    fun resolveAll(tenantKey: TenantKey): Map<FeatureKey, Boolean> {
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
            overrides[feature] ?: defaults.isEnabled(feature)
        }
    }
}
