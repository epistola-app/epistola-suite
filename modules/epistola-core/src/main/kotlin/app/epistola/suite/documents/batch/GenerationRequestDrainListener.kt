package app.epistola.suite.documents.batch

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Nudges the [JobPoller] to drain as soon as new generation requests are
 * committed, instead of leaving them to the scheduled fallback poll.
 *
 * This is what makes job start-up immediate in production and deterministic in
 * tests (no dependency on a background poll loop). Drain requests are coalesced
 * by [JobPoller.requestDrain], so a burst of events costs one drain cycle.
 *
 * The [JobPoller] bean is conditional (`epistola.generation.polling.enabled`);
 * when polling is disabled this listener quietly does nothing.
 */
@Component
class GenerationRequestDrainListener(
    private val jobPoller: ObjectProvider<JobPoller>,
) {
    @EventListener
    fun onRequestCreated(event: GenerationRequestCreatedEvent) {
        jobPoller.ifAvailable { it.requestDrain() }
    }

    @EventListener
    fun onBatchCreated(event: GenerationBatchCreatedEvent) {
        jobPoller.ifAvailable { it.requestDrain() }
    }
}
