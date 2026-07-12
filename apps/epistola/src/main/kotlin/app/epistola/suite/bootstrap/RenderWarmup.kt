package app.epistola.suite.bootstrap

import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.generation.pdf.FontCache
import app.epistola.suite.documents.batch.RenderWarmupGate
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Boot-time warmup of the PDF font/render class graph.
 *
 * The first render on a JVM class-inits iText's `OpenTypeParser`, pulls the bundled
 * TTFs out of the executable jar's nested jar, and first-loads the `PdfDocument` /
 * writer / node-renderer graph. When a burst of generation virtual threads hits that
 * first-time class loading concurrently (e.g. a sibling node dies and this node ramps
 * to its full `max-concurrent-jobs` within milliseconds), the classloader /
 * nested-jar loader monitors deadlock and wedge the whole node — which in turn arms
 * the cluster single-owner starvation in issue #723. See issue #724.
 *
 * Forcing that graph to load once, single-threaded, at startup removes the race: by
 * the time any production concurrency arrives, the classes are already loaded. Two
 * complementary passes — [FontCache.warmUp] loads the font parser (an empty warmup
 * document renders no text, so a render alone would not touch the fonts), and
 * [DirectPdfRenderer.warmUp] renders throwaway documents (single- and two-pass) to
 * load the rest of the render graph.
 *
 * The [JobPoller][app.epistola.suite.documents.batch.JobPoller] concurrent drain is
 * held back until this completes via [RenderWarmupGate]: the gate is closed here as
 * the bean is constructed (before scheduled polling starts) and re-opened when warmup
 * finishes, so the burst can never race the warmup regardless of lifecycle ordering.
 * Both warmup calls are best-effort and never fail startup; the gate is released in a
 * `finally` so a warmup failure can't wedge the poller shut.
 *
 * TODO(#724): re-check whether a newer JDK 25 patch or Spring Boot loader release
 * fixes the underlying monitor deadlock / invisible-owner diagnostics, at which
 * point this warmup may become unnecessary.
 */
@Component
@Order(RenderWarmup.RUN_ORDER)
class RenderWarmup(
    private val pdfRenderer: DirectPdfRenderer,
    private val renderWarmupGate: RenderWarmupGate,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // Close the gate as soon as this bean exists (during context refresh, before
        // the scheduled poller starts) so no drain slips through before warmup runs.
        renderWarmupGate.beginWarmup()
    }

    override fun run(args: ApplicationArguments) {
        val startedAt = System.currentTimeMillis()
        try {
            FontCache.warmUp()
            pdfRenderer.warmUp()
            log.info("Render warmup finished in {}ms", System.currentTimeMillis() - startedAt)
        } finally {
            renderWarmupGate.markReady()
        }
    }

    companion object {
        /** Ahead of other application runners so the render graph loads before any job drain. */
        const val RUN_ORDER = -100
    }
}
