// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.perf

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.assets.queries.ListImagePage
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * How long the image operations take, before and after images moved to JSON storage (ADR 0033,
 * step 6). The same file runs on both, so it uses only messages both have.
 *
 * Data set: one tenant with [CATALOGS] catalogs of [IMAGES] small PNGs each (distinct bytes), every
 * catalog released once. Cold = first call; median / p95 of [RUNS] after [WARMUP]. Results: console
 * and `build/perf-reports/image-storage.csv`.
 *
 * ```
 * ./gradlew :modules:epistola-core:perfTest --rerun --tests "*ImageStoragePerfTest"
 * ```
 */
@Tag("perf")
@Tag("integration")
class ImageStoragePerfTest : IntegrationTestBase() {

    private data class Result(val operation: String, val coldMs: Double, val medianMs: Double, val p95Ms: Double)

    private fun png(seed: Int): ByteArray = javaClass.classLoader.getResourceAsStream("epistola/catalogs/system/binaries/system-badge.svg")!!.readBytes()
        .let { it + "<!-- $seed -->".toByteArray() }

    @Test
    fun `image reads and writes on a large data set`() {
        val tenant = createTenant("Image perf").id
        withMediator {
            for (c in 0 until CATALOGS) {
                val catalog = testCatalog(tenant, CatalogKey.of("cat-$c"))
                CreateCatalog(tenantKey = tenant, id = catalog.key, name = "Catalog $c").execute()
                repeat(IMAGES) { i ->
                    UploadAsset(catalog, "Image $c/$i", AssetMediaType.SVG, png(c * 1000 + i), 16, 16, id = AssetKey.of("img-$c-$i")).execute()
                }
                mediator.releaseNext(catalog)
            }
        }
        val catalog = testCatalog(tenant, CatalogKey.of("cat-${CATALOGS / 2}"))
        val one = AssetKey.of("img-${CATALOGS / 2}-0")
        var n = 0
        val results = listOf(
            measure("list a catalog's images (page of 50)") { ListImagePage(catalog, limit = 50, offset = 0).query() },
            measure("list a tenant's images") { ListAssets(tenant).query() },
            measure("read one image") { GetAsset(tenant, one).query() },
            measure("read one image's bytes") { GetAssetContent(tenant, one).query() },
            measure("upload an image", runs = 100, warmup = 10) { UploadAsset(catalog, "New ${n++}", AssetMediaType.SVG, png(900_000 + n), 16, 16).execute() },
        )
        val report = buildString {
            appendLine("operation,cold_ms,median_ms,p95_ms")
            results.forEach { appendLine("${it.operation},${"%.2f".format(it.coldMs)},${"%.2f".format(it.medianMs)},${"%.2f".format(it.p95Ms)}") }
        }
        println("Image storage perf\n$report")
        val out = Path.of("build/perf-reports/image-storage.csv")
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
        const val CATALOGS = 20
        const val IMAGES = 25
        const val RUNS = 200
        const val WARMUP = 20
    }
}
