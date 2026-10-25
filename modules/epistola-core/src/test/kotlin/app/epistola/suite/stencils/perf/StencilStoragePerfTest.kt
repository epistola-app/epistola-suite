// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.perf

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.ListStencils
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * How long the stencil operations take, before and after stencils moved to JSON storage (ADR 0033,
 * step 4). The same file runs on both, so it uses only messages both have.
 *
 * Data set: one tenant with [CATALOGS] catalogs of [STENCILS] stencils, each with a document of
 * [NODES] text nodes and two of five tags, every catalog released once. Cold = first call; median /
 * p95 of [RUNS] after [WARMUP]. Results: console and `build/perf-reports/stencil-storage.csv`.
 *
 * ```
 * ./gradlew :modules:epistola-core:perfTest --rerun --tests "*StencilStoragePerfTest"
 * ```
 */
@Tag("perf")
@Tag("integration")
class StencilStoragePerfTest : IntegrationTestBase() {

    private data class Result(val operation: String, val coldMs: Double, val medianMs: Double, val p95Ms: Double)

    private fun document(seed: Int): TemplateDocument {
        val texts = (0 until NODES).associate { n -> "t$n" to Node(id = "t$n", type = "text", props = mapOf("content" to mapOf("type" to "doc", "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "Paragraph $seed/$n with some words in it"))))))) }
        val root = Node(id = "root", type = "root", slots = listOf("s"))
        return TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf("root" to root) + texts,
            slots = mapOf("s" to Slot(id = "s", nodeId = "root", name = "children", children = texts.keys.toList())),
        )
    }

    @Test
    fun `stencil reads and writes on a large data set`() {
        val tenant = createTenant("Stencil perf").id
        val tags = listOf("letter", "invoice", "legal", "footer", "header")
        withMediator {
            for (c in 0 until CATALOGS) {
                val catalog = testCatalog(tenant, CatalogKey.of("cat-$c"))
                CreateCatalog(tenantKey = tenant, id = catalog.key, name = "Catalog $c").execute()
                repeat(STENCILS) { s ->
                    CreateStencil(StencilId(StencilKey.of("stencil-$s"), catalog), "Stencil $c/$s", tags = listOf(tags[s % 5], tags[(s + 2) % 5]), content = document(s)).execute()
                }
                mediator.releaseNext(catalog)
            }
        }
        val catalog = testCatalog(tenant, CatalogKey.of("cat-${CATALOGS / 2}"))
        val one = StencilId(StencilKey.of("stencil-0"), catalog)
        var flip = 0
        val results = listOf(
            measure("list a catalog's stencils (page of 50)") { ListStencils(TenantId(tenant), catalog = catalog).query() },
            measure("list a tenant's stencils (page of 50)") { ListStencils(TenantId(tenant)).query() },
            measure("search a tenant's stencils by name") { ListStencils(TenantId(tenant), searchTerm = "/7").query() },
            measure("filter a tenant's stencils by tag") { ListStencils(TenantId(tenant), tag = "legal").query() },
            measure("read one stencil") { GetStencil(one).query() },
            measure("save a stencil") { UpdateStencil(one, name = "Stencil ${flip++}").execute() },
        )
        val report = buildString {
            appendLine("operation,cold_ms,median_ms,p95_ms")
            results.forEach { appendLine("${it.operation},${"%.2f".format(it.coldMs)},${"%.2f".format(it.medianMs)},${"%.2f".format(it.p95Ms)}") }
        }
        println("Stencil storage perf\n$report")
        val out = Path.of("build/perf-reports/stencil-storage.csv")
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
        const val STENCILS = 25
        const val NODES = 30
        const val RUNS = 200
        const val WARMUP = 20
    }
}
