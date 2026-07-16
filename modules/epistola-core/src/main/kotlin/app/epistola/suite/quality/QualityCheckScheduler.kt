package app.epistola.suite.quality

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Runs the in-process quality sources across every variant of every tenant that has the feature
 * available, once a day.
 *
 * This is what keeps the report page populated without anyone opening an editor. Remote sources do
 * not appear here — they push on their own schedule — so the sweep is only ever as expensive as the
 * local sources are.
 *
 * ### Idempotence
 *
 * The task lease can expire mid-run and the same occurrence be retried on another node, so the
 * handler must be safe to run twice. It is, for free: reconciliation is a full-set upsert, so a
 * second run over the same document produces the same rows, keeps `first_seen_at`, and changes no
 * statuses. That is asserted rather than assumed.
 *
 * Tenants are gated on the feature and swept under a system principal so the permission-gated
 * command authorizes. A failure on one tenant is logged and the sweep continues — unlike the backup
 * sweep, there is no hub to be unreachable, so there is nothing that would make the remaining
 * tenants fail in the same way and no reason to back off wholesale.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.quality.sweep.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class QualityCheckScheduler(
    private val mediator: Mediator,
    private val jdbi: Jdbi,
    @Value("\${epistola.quality.sweep.cron:0 30 3 * * *}")
    private val cron: String,
) : ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val taskType: String = TASK_TYPE

    @Bean
    fun qualitySweepScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(cron),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        runDailySweep()
    }

    fun runDailySweep() {
        MediatorContext.runWithMediator(mediator) { sweepAllTenants() }
    }

    private fun sweepAllTenants() {
        val tenantKeys = allTenantKeys().filter {
            ResolveAvailableFeatures(it).query()[KnownFeatures.QUALITY] == true
        }
        if (tenantKeys.isEmpty()) return

        log.info("Quality sweep starting for {} tenant(s)", tenantKeys.size)
        for (tenantKey in tenantKeys) {
            try {
                SecurityContext.runWithPrincipal(SystemUser.principalForTenant(tenantKey)) {
                    sweepTenant(tenantKey)
                }
            } catch (e: Exception) {
                log.error("Quality sweep failed for tenant {}: {}", tenantKey.value, e.message, e)
            }
        }
    }

    private fun sweepTenant(tenantKey: TenantKey) {
        for (variantId in variantsOf(tenantKey)) {
            try {
                RunQualityChecks(variantId).execute()
            } catch (e: Exception) {
                // One unhealthy variant must not cost the tenant its whole sweep.
                log.error("Quality checks failed for {}: {}", variantId.toUrn(), e.message, e)
            }
        }
    }

    /**
     * Every variant that has a document to check. Variants with no draft and no published version
     * are skipped here rather than loaded and discarded inside [RunQualityChecks].
     */
    private fun variantsOf(tenantKey: TenantKey): List<VariantId> = jdbi.withHandle<List<VariantId>, Exception> { handle ->
        handle
            .createQuery(
                """
                SELECT DISTINCT tv.catalog_key, tv.template_key, tv.id AS variant_key
                FROM template_variants tv
                WHERE tv.tenant_key = :tenantKey
                  AND EXISTS (
                      SELECT 1 FROM template_versions ver
                      WHERE ver.tenant_key = tv.tenant_key AND ver.catalog_key = tv.catalog_key
                        AND ver.template_key = tv.template_key AND ver.variant_key = tv.id
                        AND ver.status IN ('draft', 'published')
                  )
                ORDER BY tv.catalog_key, tv.template_key, variant_key
                """,
            )
            .bind("tenantKey", tenantKey)
            .map { rs, _ ->
                VariantId(
                    VariantKey.of(rs.getString("variant_key")),
                    TemplateId(
                        TemplateKey.of(rs.getString("template_key")),
                        CatalogId(CatalogKey.of(rs.getString("catalog_key")), TenantId(tenantKey)),
                    ),
                )
            }
            .list()
    }

    private fun allTenantKeys(): List<TenantKey> = jdbi.withHandle<List<TenantKey>, Exception> { handle ->
        handle
            .createQuery("SELECT id FROM tenants ORDER BY id")
            .mapTo(String::class.java)
            .list()
            .map { TenantKey.of(it) }
    }

    companion object {
        const val TASK_KEY = "quality.sweep.daily"
        const val ROUTING_KEY = "system:quality.sweep.daily"
        const val TASK_TYPE = "quality.sweep.daily"
    }
}
