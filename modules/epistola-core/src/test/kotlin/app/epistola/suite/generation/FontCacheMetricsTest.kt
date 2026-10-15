// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation

import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.generation.pdf.FontProgramCache
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FontCacheMetricsTest {

    private val liberation = javaClass.classLoader.getResourceAsStream("fonts/LiberationSans-Regular.ttf")!!.use { it.readBytes() }

    /** Distinct content per [n]; trailing bytes do not affect parsing. */
    private fun face(n: Int) = liberation + ByteArray(n + 1) { n.toByte() }

    @Test
    fun `publishes occupancy, limits, hit rate and evictions per limit`() {
        val cache = FontProgramCache(maxBytes = 512L * 1024 * 1024, maxFaces = 1)
        val registry = SimpleMeterRegistry()
        FontCacheMetrics(DirectPdfRenderer(fontPrograms = cache)).bindTo(registry)

        val render = cache.scope()
        render.program(face(1))
        render.program(face(1))
        render.program(face(2))
        cache.scope().program(face(2))

        fun gauge(name: String) = registry.get("epistola.generation.font_cache.$name").gauge().value()
        fun counter(name: String, tag: String, value: String) = registry.get("epistola.generation.font_cache.$name").tag(tag, value).functionCounter().count()

        assertThat(gauge("faces")).isEqualTo(1.0)
        assertThat(gauge("size.bytes")).isEqualTo(cache.bytes.toDouble()).isGreaterThan(liberation.size.toDouble())
        assertThat(gauge("max.faces")).isEqualTo(1.0)
        assertThat(gauge("max.bytes")).isEqualTo(512.0 * 1024 * 1024)
        assertThat(gauge("protected.faces")).isEqualTo(0.0) // one face may not be protected: 80% of 1 is 0
        assertThat(gauge("protected.bytes")).isEqualTo(0.0)
        assertThat(registry.get("epistola.generation.font_cache.promotions").functionCounter().count()).isEqualTo(1.0)
        assertThat(counter("requests", "result", "hit")).isEqualTo(2.0)
        assertThat(counter("requests", "result", "miss")).isEqualTo(2.0)
        assertThat(counter("evictions", "limit", "faces")).isEqualTo(1.0)
        assertThat(counter("evictions", "limit", "bytes")).isEqualTo(0.0)
    }
}
