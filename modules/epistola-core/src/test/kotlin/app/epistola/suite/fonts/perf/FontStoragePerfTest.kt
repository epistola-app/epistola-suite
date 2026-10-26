// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.perf

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.fonts.queries.ResolveFontFace
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.FontFixtures
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * How long the font operations take, before and after fonts moved to JSON storage (ADR 0033,
 * step 5). The same file runs on both, so it uses only messages both have, through [FontFixtures].
 *
 * Data set: one tenant with [CATALOGS] catalogs of [FONTS] families of two uploaded faces each,
 * every catalog released once, beside the system catalog's nine families. Cold = first call;
 * median / p95 of [RUNS] after [WARMUP]. Results: console and `build/perf-reports/font-storage.csv`.
 *
 * ```
 * ./gradlew :modules:epistola-core:perfTest --rerun --tests "*FontStoragePerfTest"
 * ```
 */
@Tag("perf")
@Tag("integration")
class FontStoragePerfTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    private data class Result(val operation: String, val coldMs: Double, val medianMs: Double, val p95Ms: Double)

    @Test
    fun `font reads and writes on a large data set`() {
        val tenant = createTenant("Font perf").id
        val regular = resourceLoader.getResource("classpath:epistola/catalogs/system/binaries/fonts/lato/lato-Regular.ttf").contentAsByteArray
        val bold = resourceLoader.getResource("classpath:epistola/catalogs/system/binaries/fonts/lato/lato-Bold.ttf").contentAsByteArray
        withMediator {
            for (c in 0 until CATALOGS) {
                val catalog = testCatalog(tenant, CatalogKey.of("cat-$c"))
                CreateCatalog(tenantKey = tenant, id = catalog.key, name = "Catalog $c").execute()
                repeat(FONTS) { f -> FontFixtures.importFont(catalog, "font-$f", "Font $c/$f", mapOf((400 to false) to regular, (700 to false) to bold)) }
                mediator.releaseNext(catalog)
            }
        }
        val catalog = testCatalog(tenant, CatalogKey.of("cat-${CATALOGS / 2}"))
        val one = FontId(FontKey.of("font-0"), catalog)
        var flip = 0
        val results = listOf(
            measure("list a catalog's fonts") { ListFonts(TenantId(tenant), catalog = catalog).query() },
            measure("list a tenant's fonts") { ListFonts(TenantId(tenant)).query() },
            measure("read a font's faces") { GetFontVariants(one).query() },
            measure("resolve a face's bytes") { ResolveFontFace(catalog, FontKey.of("font-0"), 700, false).query() },
            measure("save a font", runs = 50, warmup = 5) { FontFixtures.importFont(catalog, "font-0", "Font ${flip++}", mapOf((400 to false) to regular, (700 to false) to bold)) },
        )
        val report = buildString {
            appendLine("operation,cold_ms,median_ms,p95_ms")
            results.forEach { appendLine("${it.operation},${"%.2f".format(it.coldMs)},${"%.2f".format(it.medianMs)},${"%.2f".format(it.p95Ms)}") }
        }
        println("Font storage perf\n$report")
        val out = Path.of("build/perf-reports/font-storage.csv")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
    }

    private fun measure(operation: String, runs: Int = RUNS, warmup: Int = WARMUP, block: () -> Unit): Result = withMediator {
        val cold = timed(block)
        repeat(warmup) { block() }
        val samples = (1..runs).map { timed(block) }.sorted()
        Result(operation, cold, samples[samples.size / 2], samples[(samples.size * 95) / 100 - 1])
    }

    private fun timed(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1e6
    }

    private companion object {
        const val CATALOGS = 10
        const val FONTS = 10
        const val RUNS = 200
        const val WARMUP = 20
    }
}
