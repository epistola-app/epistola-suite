package app.epistola.suite.support.upgrading

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.port.InstallationStore
import app.epistola.hub.proto.v1.ListCompatibilityResultsRequest
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.upgrading.CompatibilityCheckResult
import app.epistola.suite.upgrading.CompatibilitySyncPort
import app.epistola.suite.upgrading.CompatibilityVerdict
import com.google.protobuf.Timestamp
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import app.epistola.hub.proto.v1.CompatibilityVerdict as ProtoVerdict

/**
 * Production [CompatibilitySyncPort] that reads compatibility-check results from epistola-hub over
 * gRPC. Authentication is installation-wide. Wired only when `epistola.support.enabled=true`;
 * otherwise the no-op adapter is used. Hub errors (e.g. `HubEntitlementDeniedException`) propagate
 * so the UI can surface them.
 */
class HubCompatibilitySyncAdapter(
    private val client: EpistolaHubClient,
    private val installationStore: InstallationStore,
) : CompatibilitySyncPort {
    @Volatile
    private var registered = false

    override fun isEnabled(): Boolean = true

    override fun isReady(): Boolean {
        if (registered) return true
        val ready = installationStore.load() != null
        if (ready) registered = true
        return ready
    }

    override fun listCompatibilityResults(tenantKey: TenantKey): List<CompatibilityCheckResult> {
        val response =
            client.listCompatibilityResults(
                ListCompatibilityResultsRequest.newBuilder().setTenant(tenantKey.value).build(),
            )
        return response.resultsList.map { r ->
            CompatibilityCheckResult(
                tenant = r.tenant,
                targetVersion = r.targetVersion,
                snapshotId = r.snapshotId.ifBlank { null },
                catalogKey = r.catalogKey.ifBlank { null },
                verdict = r.verdict.toDomain(),
                detail = r.detail.ifBlank { null },
                occurredAt = r.occurredAt.toInstant(),
            )
        }
    }
}

/**
 * Wires the hub-backed compatibility read. Active only when `epistola.support.enabled=true`;
 * registering it overrides the no-op fallback.
 */
@Configuration
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class SupportUpgradingConfiguration {
    @Bean
    fun compatibilitySyncPort(
        client: EpistolaHubClient,
        installationStore: InstallationStore,
    ): CompatibilitySyncPort = HubCompatibilitySyncAdapter(client, installationStore)
}

private fun ProtoVerdict.toDomain(): CompatibilityVerdict = when (this) {
    ProtoVerdict.COMPATIBILITY_VERDICT_PASS -> CompatibilityVerdict.PASS
    ProtoVerdict.COMPATIBILITY_VERDICT_WARN -> CompatibilityVerdict.WARN
    ProtoVerdict.COMPATIBILITY_VERDICT_FAIL -> CompatibilityVerdict.FAIL
    ProtoVerdict.COMPATIBILITY_VERDICT_UNSPECIFIED, ProtoVerdict.UNRECOGNIZED -> CompatibilityVerdict.UNKNOWN
}

private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())
