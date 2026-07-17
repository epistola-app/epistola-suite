package app.epistola.suite.quality

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.variants.ListVariants
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
     * Every variant in the tenant, read through core's own queries rather than this module
     * querying core's tables — so the sweep depends on core's contract, not its schema.
     *
     * One query per template rather than a single join: the sweep is nightly and a tenant has tens
     * of templates, so the round trips are free. Variants with no draft and no published version
     * are not filtered out here; [RunQualityChecks] resolves no document for them and returns
     * without running a source — the same outcome for one extra call.
     *
     * **Paged deliberately.** `ListDocumentTemplates` defaults to `limit = 50`, so calling it plainly
     * would sweep a tenant's first 50 templates and silently skip the rest — a partial sweep that
     * looks exactly like a complete one. A sweep must cover everything or say that it didn't.
     */
    private fun variantsOf(tenantKey: TenantKey): List<VariantId> = allTemplatesOf(tenantKey)
        .flatMap { template ->
            val templateId = TemplateId(template.id, CatalogId(template.catalogKey, TenantId(tenantKey)))
            ListVariants(templateId).query().map { variant -> VariantId(variant.id, templateId) }
        }

    private fun allTemplatesOf(tenantKey: TenantKey): List<DocumentTemplate> {
        val tenantId = TenantId(tenantKey)
        val all = mutableListOf<DocumentTemplate>()
        var offset = 0
        while (true) {
            val page = ListDocumentTemplates(tenantId, limit = TEMPLATE_PAGE_SIZE, offset = offset).query()
            all += page
            if (page.size < TEMPLATE_PAGE_SIZE) return all
            offset += TEMPLATE_PAGE_SIZE
        }
    }

    /**
     * The one place this module reads a core table directly, and deliberately.
     *
     * `ListTenants` is `RequiresAuthentication`, but the sweep has no principal to offer yet: it
     * enumerates tenants precisely so it can bind `SystemUser.principalForTenant(tenant)` for each
     * one, and that principal is per-tenant by construction. `BackupScheduler` — also a feature
     * module outside core — reads `tenants` raw for exactly this reason.
     */
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

        /** Page size for template enumeration — the sweep must see every template, so it pages. */
        private const val TEMPLATE_PAGE_SIZE = 200
    }
}
