// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Checks a variant when it is marked ready, so the report reflects it without waiting for an author
 * to open the editor and click "Check now".
 *
 * Marking ready is rare, deliberate, and the moment the document is declared fit to be released,
 * which other people will rely on. Saves are checked too, a few seconds after the last one
 * ([OnVariantSavedScheduleChecks]); a ready mark does not wait for that delay.
 *
 * ### Why a timer, due now
 *
 * The check runs on the variant's quality-check timer ([QualityCheckTimer]) rather than in the request
 * that marked it: a language checker may be a server across the network, and the author marking a
 * variant ready should not wait on it. The same timer key means a save just before the mark is
 * checked once, not twice.
 *
 * ### Why IMMEDIATE
 *
 * The timer is written in the mark's own transaction, like an outbox: a mark that rolls back leaves no
 * timer. No source runs here, so a broken source can never roll back an author's mark; it runs later,
 * on the timer.
 *
 * This handler was `AFTER_COMMIT` until the language-check pilot, and never ran: the mediator invokes
 * `IMMEDIATE` event handlers only, so marking a variant ready checked nothing.
 */
@Component
class OnVariantReadyRunChecks : EventHandler<MarkVariantReady> {
    override val phase = EventPhase.IMMEDIATE

    override fun on(
        event: MarkVariantReady,
        result: Any?,
    ) {
        if (!QualityCheckTimer.enabledFor(event.variantId.tenantKey)) return

        log.debug("Checking {} after it was marked ready", event.variantId.toUrn())
        QualityCheckTimer.schedule(event.variantId, Duration.ZERO, CheckTrigger.READY)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OnVariantReadyRunChecks::class.java)
    }
}
