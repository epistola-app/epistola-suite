// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.cluster.timers.ClusterTimer
import app.epistola.suite.cluster.timers.ClusterTimerHandler
import app.epistola.suite.cluster.timers.ClusterTimerResult
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

    fun schedule(variantId: VariantId, delay: Duration) {
        val key = "$TYPE:${variantId.toUrn()}"
        ScheduleClusterTimer(
            timerKey = key,
            routingKey = key,
            timerType = TYPE,
            dueAt = EpistolaClock.offsetDateTime().plus(delay),
            payload = mapOf(
                "tenant" to variantId.tenantKey.value,
                "catalog" to variantId.catalogId.address,
                "template" to variantId.templateKey.value,
                "variant" to variantId.key.value,
            ),
            tenantKey = variantId.tenantKey,
        ).execute()
    }

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
        if (QualityCheckTimer.enabledFor(variantId.tenantKey)) RunQualityChecks(variantId).execute()
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
        QualityCheckTimer.schedule(event.variantId, properties.checkOnSaveDelay)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OnVariantSavedScheduleChecks::class.java)
    }
}
