package app.epistola.suite.quality

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.templates.commands.versions.PublishVersion
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Re-runs the in-process checks when a version is published, so the report reflects a publish
 * without waiting for the nightly sweep.
 *
 * ### Why publish and not every draft save
 *
 * Nothing is wired to `UpdateDraft`. The editor autosaves every few seconds, so a handler there
 * would run every source on every keystroke-batch, for every author, forever — and the findings
 * would be obsolete before they landed. Authors get an explicit "Check now" in the editor instead,
 * and the ledger stays honest in the meantime by marking pre-edit findings outdated.
 *
 * Publish is different: it is rare, deliberate, and the moment the document becomes something other
 * people will rely on.
 *
 * ### Why AFTER_COMMIT
 *
 * Failure is isolated at this phase, which is what we want: a quality check is an observation about
 * a publish, not a condition of it. A broken source must never be able to roll back an author's
 * publish. The nightly sweep picks up anything missed here.
 */
@Component
class OnVersionPublishedRunChecks : EventHandler<PublishVersion> {
    override val phase = EventPhase.AFTER_COMMIT

    override fun on(
        event: PublishVersion,
        result: Any?,
    ) {
        val tenantKey = event.versionId.tenantKey
        if (ResolveAvailableFeatures(tenantKey).query()[KnownFeatures.QUALITY] != true) return

        log.debug("Running quality checks after publish of {}", event.versionId.toUrn())
        RunQualityChecks(event.versionId.variantId).execute()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OnVersionPublishedRunChecks::class.java)
    }
}
