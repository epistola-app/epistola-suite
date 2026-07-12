package app.epistola.suite.documents.batch

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gate that holds the [JobPoller] back from draining generation jobs until the PDF
 * render class graph has been warmed (issue #724).
 *
 * Draining is a *concurrent* burst of first-time class loading (many virtual threads
 * entering iText font parsing / nested-jar access at once). If that burst runs before
 * the single-threaded warmup finishes, the classloader / nested-jar loader monitors
 * can deadlock — the very thing the warmup exists to prevent. Rather than rely on
 * startup lifecycle ordering (an `ApplicationRunner` warmup vs. the scheduled poll),
 * the poller explicitly waits on this signal, so "no concurrent drain before the graph
 * is warm" is enforced, not merely likely.
 *
 * Defaults to **open** so environments with no warmup provider (module/integration
 * tests, minimal deployments) drain immediately; the warmup provider closes it while
 * warming and re-opens it when done — even if the warmup throws, so a node is never
 * permanently blocked from draining.
 */
@Component
class RenderWarmupGate {
    private val open = AtomicBoolean(true)

    /** Called by the warmup before it starts, so draining defers until it completes. */
    fun beginWarmup() = open.set(false)

    /** Called by the warmup when it finishes (success or failure), releasing the poller. */
    fun markReady() = open.set(true)

    /** True once draining generation jobs is safe (render graph warm, or no warmup configured). */
    val isReady: Boolean get() = open.get()
}
