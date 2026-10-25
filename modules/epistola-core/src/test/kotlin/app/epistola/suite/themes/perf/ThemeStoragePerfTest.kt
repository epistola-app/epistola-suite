// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes.perf

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.Theme
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.ListThemes
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * How long the theme operations take, on a data set the size of a busy installation: before and after
 * themes moved to JSON storage (ADR 0033, step 1). The same file runs on both, so it uses only
 * messages both have.
 *
 * Data set: [TENANTS] tenants, each with [CATALOGS] authored catalogs of [THEMES_PER_CATALOG] themes,
 * every catalog released once (so each holds a release and its next version), the themes carrying
 * the bundled system themes' styles, page settings and presets. Measured in the first tenant.
 *
 * Measured twice: straight after seeding, before the planner has statistics on the grown tables (a
 * fresh installation's first minutes), and after an `ANALYZE`, as an installation runs.
 *
 * - **cold**: the first call of each operation in the phase.
 * - **cached**: the median and 95th percentile of [RUNS] calls after [WARMUP] more.
 *
 * Seeding reports what creating a theme and releasing a catalog took on average, as the data set grew.
 *
 * Through the mediator, as a handler calls it, so authorization, targeting and the transaction count.
 * Results: console, and `build/perf-reports/theme-storage.csv`.
 *
 * ```
 * ./gradlew :modules:epistola-core:perfTest --tests "*ThemeStoragePerfTest"
 * ```
 */
@Tag("perf")
@Tag("integration")
class ThemeStoragePerfTest : IntegrationTestBase() {

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    private data class Result(val operation: String, val coldMs: Double, val medianMs: Double, val p95Ms: Double)

    @Test
    fun `theme reads and writes on a large data set`() {
        val seeded = System.nanoTime()
        val tenants = (1..TENANTS).map { seed(it) }
        val seedSeconds = (System.nanoTime() - seeded) / 1e9
        val tenant = tenants.first()
        val catalog = testCatalog(tenant, CatalogKey.of("cat-${CATALOGS / 2}"))
        val one = ThemeId(ThemeKey.of("theme-${THEMES_PER_CATALOG / 2}"), catalog)

        var flip = 0
        var draft = 0
        fun phase(name: String) = listOf(
            measure("$name,list a catalog's themes") { ListThemes(TenantId(tenant), catalog = catalog).query() },
            measure("$name,list a tenant's themes") { ListThemes(TenantId(tenant)).query() },
            measure("$name,read one theme") { GetTheme(one).query() },
            measure("$name,save a theme") { UpdateTheme(one, name = "Theme ${flip++}").execute() },
            measure("$name,start a draft", runs = 40, warmup = 5) {
                // Each on a catalog of its own, so no catalog collects drafts as the runs go on.
                val on = testCatalog(tenant, CatalogKey.of("cat-${draft++ % CATALOGS}"))
                CreateCatalogDraft(on, "Work $draft").execute()
            },
        )

        // Straight after seeding, before autoanalyze has run: a fresh installation's first minutes.
        val fresh = phase("before statistics")
        // Then as an installation runs, with statistics (the test analyzes; autoanalyze would).
        jdbi.useHandle<Exception> { it.execute("ANALYZE") }
        val results = fresh + phase("with statistics")

        val report = buildString {
            appendLine("phase,operation,cold_ms,median_ms,p95_ms")
            results.forEach { appendLine("${it.operation},${"%.2f".format(it.coldMs)},${"%.2f".format(it.medianMs)},${"%.2f".format(it.p95Ms)}") }
        }
        println("Theme storage perf: $TENANTS tenants x $CATALOGS catalogs x $THEMES_PER_CATALOG themes, seeded in ${"%.0f".format(seedSeconds)} s (creating themes ${"%.1f".format(createNanos / 1e9 / (TENANTS * CATALOGS * THEMES_PER_CATALOG) * 1000)} ms each, releasing ${"%.0f".format(releaseNanos / 1e6 / (TENANTS * CATALOGS))} ms a catalog)\n$report")
        val out = Path.of("build/perf-reports/theme-storage.csv")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
    }

    private var createNanos = 0L
    private var releaseNanos = 0L

    private fun seed(index: Int): TenantKey = withMediator {
        val tenant = createTenant("Theme perf $index").id
        val styles: List<Theme> = ListThemes(TenantId(tenant), catalog = systemCatalog(tenant)).query()
        for (c in 0 until CATALOGS) {
            val key = CatalogKey.of("cat-$c")
            CreateCatalog(tenantKey = tenant, id = key, name = "Catalog $c").execute()
            val catalog: CatalogId = testCatalog(tenant, key)
            val created = System.nanoTime()
            for (t in 0 until THEMES_PER_CATALOG) {
                val like = styles[(c + t) % styles.size]
                CreateTheme(
                    ThemeId(ThemeKey.of("theme-$t"), catalog),
                    "Theme $c/$t",
                    description = like.description,
                    documentStyles = like.documentStyles,
                    pageSettings = like.pageSettings,
                    blockStylePresets = like.blockStylePresets,
                    spacingUnit = like.spacingUnit,
                ).execute()
            }
            createNanos += System.nanoTime() - created
            val released = System.nanoTime()
            mediator.releaseNext(catalog)
            releaseNanos += System.nanoTime() - released
        }
        tenant
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
        const val TENANTS = 3
        const val CATALOGS = 40
        const val THEMES_PER_CATALOG = 25
        const val RUNS = 200
        const val WARMUP = 20
    }
}
