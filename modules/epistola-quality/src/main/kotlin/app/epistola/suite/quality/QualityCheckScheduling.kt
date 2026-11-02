// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.cluster.timers.ClusterTimer
import app.epistola.suite.cluster.timers.ClusterTimerHandler
import app.epistola.suite.cluster.timers.ClusterTimerResult
import app.epistola.suite.cluster.timers.ClusterTimerStatus
import app.epistola.suite.cluster.timers.GetClusterTimer
import app.epistola.suite.cluster.timers.ScheduleClusterTimer
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
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * A variant's checks, as a cluster timer (ADR 0044, section 6; `docs/timers.md`).
 *
 * One timer per variant, keyed by it: scheduling again before it ran moves it, so a burst of
 * autosaves is checked once, after the last, and a ready mark in the meantime joins the same run. The
 * timer is durable and leased, so the check runs once in the cluster, on whichever node owns it, and
 * runs again after a crash; a run is idempotent (cached results, a reconciling ledger), so running
 * twice costs nothing but the time.
 */
object QualityCheckTimer {
    const val TYPE = "quality-check"

    /**
     * Schedules the variant's check for [trigger], [delay] from now, joining a check already pending:
     * the run covers every trigger that asked, so a save and a ready mark coalesced into one run still
     * ask the providers configured for either. A pending ready mark keeps its time; a later save does
     * not push it back. Read and rewrite are not atomic: two triggers at the same instant may leave one
     * out, and the next save brings it back.
     */
    fun schedule(variantId: VariantId, delay: Duration, trigger: CheckTrigger) {
        val key = "$TYPE:${variantId.toUrn()}"
        val pending = GetClusterTimer(key).query()?.takeIf { it.status == ClusterTimerStatus.SCHEDULED }
        val pendingTriggers = pending?.let(::triggersOf).orEmpty()
        val triggers = pendingTriggers + trigger
        val dueAt = EpistolaClock.offsetDateTime().plus(delay).let { due ->
            if (pending != null && pendingTriggers.any { it != CheckTrigger.SAVE } && pending.dueAt.isBefore(due)) pending.dueAt else due
        }
        ScheduleClusterTimer(
            timerKey = key,
            routingKey = key,
            timerType = TYPE,
            dueAt = dueAt,
            payload = mapOf(
                "tenant" to variantId.tenantKey.value,
                "catalog" to variantId.catalogId.address,
                "template" to variantId.templateKey.value,
                "variant" to variantId.key.value,
                "triggers" to triggers.map { it.name }.sorted(),
            ),
            tenantKey = variantId.tenantKey,
        ).execute()
    }

    /** The triggers a timer was set for; a timer from before triggers were recorded checks as a save. */
    fun triggersOf(timer: ClusterTimer): Set<CheckTrigger> = (timer.payload["triggers"] as? List<*>)
        ?.mapNotNull { name -> CheckTrigger.entries.firstOrNull { it.name == name } }
        ?.toSet()
        ?.ifEmpty { null }
        ?: setOf(CheckTrigger.SAVE)

    fun variantOf(timer: ClusterTimer): VariantId {
        fun field(name: String) = requireNotNull(timer.payload[name] as? String) { "quality-check timer ${timer.timerKey} has no $name" }
        val tenantId = TenantId(TenantKey.of(field("tenant")))
        return VariantId(VariantKey.of(field("variant")), TemplateId(TemplateKey.of(field("template")), CatalogId.of(field("catalog"), tenantId)))
    }

    /** Whether the tenant has the quality feature; a variant of a tenant without it is never checked. */
    fun enabledFor(tenantKey: TenantKey): Boolean = ResolveAvailableFeatures(tenantKey).query()[KnownFeatures.QUALITY] == true
}

/** Runs a variant's checks when its timer is due, as the tenant's system user (`ClusterTimerScheduler`). */
@Component
class QualityCheckTimerHandler : ClusterTimerHandler {
    override val timerType = QualityCheckTimer.TYPE

    override fun handle(timer: ClusterTimer): ClusterTimerResult {
        val variantId = QualityCheckTimer.variantOf(timer)
        // The feature may have been switched off since the timer was set.
        if (QualityCheckTimer.enabledFor(variantId.tenantKey)) {
            RunQualityChecks(variantId, triggers = QualityCheckTimer.triggersOf(timer)).execute()
        }
        return ClusterTimerResult.Complete
    }
}

/**
 * Checks a variant after it is saved, from the editor, REST or MCP alike: they all save through
 * [UpdateVariantContent]. The check runs [QualityProperties.checkOnSaveDelay] after the last save, so
 * an author typing through a few autosaves gets one check, and the language checkers are asked only
 * about the paragraphs that changed (ADR 0044).
 *
 * A save to a draft other than the catalog's next version is not checked yet: the ledger keeps no
 * version, and a check reads the next version's variant (ADR 0044, section 8, not built).
 *
 * ### Why IMMEDIATE
 *
 * The timer is a row written in the save's own transaction, like an outbox: a save that rolls back
 * leaves no timer, and the scheduler only ever sees committed rows, so a check never runs for a save
 * that did not happen. The check itself runs later, on the timer, never in the save.
 */
@Component
class OnVariantSavedScheduleChecks(
    private val properties: QualityProperties,
) : EventHandler<UpdateVariantContent> {
    override val phase = EventPhase.IMMEDIATE

    override fun on(
        event: UpdateVariantContent,
        result: Any?,
    ) {
        if (!properties.checkOnSave || event.catalogVersion != null) return
        if (!QualityCheckTimer.enabledFor(event.variantId.tenantKey)) return
        log.debug("Checking {} after its save", event.variantId.toUrn())
        QualityCheckTimer.schedule(event.variantId, properties.checkOnSaveDelay, CheckTrigger.SAVE)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OnVariantSavedScheduleChecks::class.java)
    }
}
