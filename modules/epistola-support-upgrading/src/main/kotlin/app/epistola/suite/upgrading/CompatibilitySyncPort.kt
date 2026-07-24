// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.upgrading

import app.epistola.suite.common.ids.TenantKey
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

/**
 * Port for reading compatibility-check results from an external target.
 *
 * The production implementation (`HubCompatibilitySyncAdapter`) reads results recorded by the
 * company side on epistola-hub; [NoOpCompatibilitySyncAdapter] is the default when the support
 * tier is disabled. This is read-only — the suite never writes compatibility results.
 */
interface CompatibilitySyncPort {
    /** True when a real target is wired (i.e. not the no-op). */
    fun isEnabled(): Boolean

    /** True once the installation has registered with the hub. Defaults true for the no-op. */
    fun isReady(): Boolean = true

    /** Compatibility-check results recorded for a tenant, newest first. */
    fun listCompatibilityResults(tenantKey: TenantKey): List<CompatibilityCheckResult>
}

enum class CompatibilityVerdict { PASS, WARN, FAIL, UNKNOWN }

data class CompatibilityCheckResult(
    val tenant: String,
    val targetVersion: String,
    val snapshotId: String?,
    val catalogKey: String?,
    val verdict: CompatibilityVerdict,
    val detail: String?,
    val occurredAt: Instant,
)

/** Fallback used when the support tier is disabled: reports disabled and returns no results. */
class NoOpCompatibilitySyncAdapter : CompatibilitySyncPort {
    override fun isEnabled(): Boolean = false

    override fun listCompatibilityResults(tenantKey: TenantKey): List<CompatibilityCheckResult> = emptyList()
}

/**
 * Registers the no-op adapter when the support tier is **off**, mutually exclusive with the hub
 * adapter's `epistola.support.enabled=true` so the two never both register (an
 * `@ConditionalOnMissingBean`-only fallback can race the hub adapter). `@ConditionalOnMissingBean`
 * is kept so an explicit/test override still wins.
 */
@Configuration
class CompatibilitySyncFallbackConfiguration {
    @Bean
    @ConditionalOnMissingBean(CompatibilitySyncPort::class)
    @ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun noOpCompatibilitySyncAdapter(): CompatibilitySyncPort = NoOpCompatibilitySyncAdapter()
}
