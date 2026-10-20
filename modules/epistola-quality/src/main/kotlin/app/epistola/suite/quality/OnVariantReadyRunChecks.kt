// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Re-runs the in-process checks when a variant is marked ready, so the report reflects it without
 * waiting for an author to open the editor and click "Check now".
 *
 * ### Why ready and not every save
 *
 * Nothing is wired to `UpdateVariantContent`. The editor autosaves every few seconds, so a handler there
 * would run every source on every keystroke-batch, for every author, forever — and the findings
 * would be obsolete before they landed. Authors get an explicit "Check now" in the editor instead,
 * and the ledger stays honest in the meantime by marking pre-edit findings outdated.
 *
 * Marking ready is different: it is rare, deliberate, and the moment the document is declared fit to
 * be released, which other people will rely on.
 *
 * ### Why AFTER_COMMIT
 *
 * Failure is isolated at this phase, which is what we want: a quality check is an observation about
 * a ready mark, not a condition of it. A broken source must never be able to roll back an author's
 * mark. If this fails, the author can still run checks explicitly from the editor.
 */
@Component
class OnVariantReadyRunChecks : EventHandler<MarkVariantReady> {
    override val phase = EventPhase.AFTER_COMMIT

    override fun on(
        event: MarkVariantReady,
        result: Any?,
    ) {
        val tenantKey = event.variantId.tenantKey
        if (ResolveAvailableFeatures(tenantKey).query()[KnownFeatures.QUALITY] != true) return

        log.debug("Running quality checks after {} was marked ready", event.variantId.toUrn())
        RunQualityChecks(event.variantId).execute()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OnVariantReadyRunChecks::class.java)
    }
}
