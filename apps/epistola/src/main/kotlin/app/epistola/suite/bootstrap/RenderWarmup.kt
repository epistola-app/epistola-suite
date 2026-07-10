package app.epistola.suite.bootstrap

import app.epistola.generation.pdf.FontCache
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Boot-time warmup of the PDF font/render class graph.
 *
 * The first [FontCache] build on a JVM class-inits iText's `OpenTypeParser` and
 * pulls the bundled TTFs out of the executable jar's nested jar. When a burst of
 * generation virtual threads hits that first-time class loading concurrently
 * (e.g. a sibling node dies and this node ramps to its full `max-concurrent-jobs`
 * within milliseconds), the classloader / nested-jar loader monitors deadlock and
 * wedge the whole node — which in turn arms the cluster single-owner starvation in
 * issue #723. See issue #724.
 *
 * Forcing that class graph to load once, single-threaded, at startup removes the
 * race: by the time any production concurrency arrives, the classes are already
 * loaded. Runs with high precedence so the warmup wins against any pending-job
 * drain that starts on boot; the call is best-effort and never fails startup.
 *
 * TODO(#724): re-check whether a newer JDK 25 patch or Spring Boot loader release
 * fixes the underlying monitor deadlock / invisible-owner diagnostics, at which
 * point this warmup may become unnecessary.
 */
@Component
@Order(RenderWarmup.RUN_ORDER)
class RenderWarmup : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val startedAt = System.currentTimeMillis()
        FontCache.warmUp()
        log.info("Render warmup finished in {}ms", System.currentTimeMillis() - startedAt)
    }

    companion object {
        /** Ahead of other application runners so the font classes load before any job drain. */
        const val RUN_ORDER = -100
    }
}
