// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation

import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.generation.pdf.FontProgramCache
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/**
 * Publishes the renderer's parsed-font cache ([FontProgramCache]) per node, so its two limits
 * (`epistola.generation.font-cache.*`) can be tuned: occupancy against each limit, the hit rate, and
 * which limit evicts. Evictions under `limit=faces` mean `max-faces` binds; under `limit=bytes`,
 * `max-size` does.
 */
@Component
class FontCacheMetrics(
    renderer: DirectPdfRenderer,
) : MeterBinder {

    private val cache: FontProgramCache = renderer.fontPrograms

    override fun bindTo(registry: MeterRegistry) {
        Gauge.builder("$PREFIX.size.bytes", cache) { it.bytes.toDouble() }
            .description("Estimated heap held by parsed fonts, measured as what parsing them allocated")
            .baseUnit("bytes")
            .register(registry)
        Gauge.builder("$PREFIX.faces", cache) { it.faces.toDouble() }
            .description("Parsed font faces held")
            .register(registry)
        Gauge.builder("$PREFIX.protected.bytes", cache) { it.protectedBytes.toDouble() }
            .description("Estimated heap held by faces more than one render has used, which eviction takes last")
            .baseUnit("bytes")
            .register(registry)
        Gauge.builder("$PREFIX.protected.faces", cache) { it.protectedFaces.toDouble() }
            .description("Faces more than one render has used, which eviction takes last")
            .register(registry)
        Gauge.builder("$PREFIX.max.bytes", cache) { it.maxBytes.toDouble() }
            .description("Configured limit on the estimated heap (epistola.generation.font-cache.max-size)")
            .baseUnit("bytes")
            .register(registry)
        Gauge.builder("$PREFIX.max.faces", cache) { it.maxFaces.toDouble() }
            .description("Configured limit on faces (epistola.generation.font-cache.max-faces)")
            .register(registry)

        FunctionCounter.builder("$PREFIX.requests", cache) { it.stats().hits.toDouble() }
            .description("Font lookups, served from the cache (hit) or parsed (miss)")
            .tag("result", "hit")
            .register(registry)
        FunctionCounter.builder("$PREFIX.requests", cache) { it.stats().misses.toDouble() }
            .description("Font lookups, served from the cache (hit) or parsed (miss)")
            .tag("result", "miss")
            .register(registry)
        FunctionCounter.builder("$PREFIX.promotions", cache) { it.stats().promotions.toDouble() }
            .description("Faces a second render used, moving them to the protected segment")
            .register(registry)
        FunctionCounter.builder("$PREFIX.evictions", cache) { it.stats().byteLimitEvictions.toDouble() }
            .description("Faces evicted, by the limit that evicted them")
            .tag("limit", "bytes")
            .register(registry)
        FunctionCounter.builder("$PREFIX.evictions", cache) { it.stats().faceLimitEvictions.toDouble() }
            .description("Faces evicted, by the limit that evicted them")
            .tag("limit", "faces")
            .register(registry)
    }

    private companion object {
        const val PREFIX = "epistola.generation.font_cache"
    }
}
