package app.epistola.suite.support

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorExecutionContext
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns a hub-observed entitlements revision into a refresh. The hub stamps the current revision on
 * every response; [EpistolaHubClient.addEntitlementsRevisionListener] delivers it here (on the RPC
 * thread). When the observed revision is newer than the one our stored set carries, we refresh
 * asynchronously — so an entitlement change propagates on the next hub call the Suite makes, between
 * the (long) backstop polls. Concurrent observations coalesce into a single in-flight refresh.
 *
 * Only constructed when the support tier is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class EntitlementRevisionTrigger(
    private val store: EntitlementStore,
    private val entitlementSync: EntitlementSyncService,
    private val mediator: Mediator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // One virtual thread for refreshes; the RPC thread that observes the header never blocks.
    private val executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("entitlement-rev-refresh-", 0).factory())
    private val refreshing = AtomicBoolean(false)

    /** Called with the revision seen on a hub response header. Cheap and non-blocking. */
    fun observe(revision: Long) {
        val storedRevision = store.load()?.revision ?: -1L
        if (revision <= storedRevision) return
        // Coalesce: if a refresh is already running it will pull the latest revision anyway.
        if (!refreshing.compareAndSet(false, true)) return
        val context = MediatorExecutionContext.capture(mediator)
        executor.execute(
            context.runnable {
                try {
                    log.debug("Hub signalled entitlements revision {} > stored {}; refreshing", revision, storedRevision)
                    entitlementSync.refresh()
                } finally {
                    refreshing.set(false)
                }
            },
        )
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
