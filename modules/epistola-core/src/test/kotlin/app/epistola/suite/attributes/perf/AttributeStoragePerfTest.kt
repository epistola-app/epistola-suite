// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.perf

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.DeleteCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.variants.validateAttributes
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * How long the attribute operations take, before and after attributes moved to JSON storage (ADR
 * 0033, step 3). The same file runs on both, so it uses only messages both have.
 *
 * Data set: one tenant with [CATALOGS] catalogs, each with [LISTS] code lists of 50 entries and
 * [ATTRIBUTES] attributes (a third bound to a code list of their own catalog, a third bound to one of
 * the first catalog's, a third with inline values), every catalog released once. Cold = first call;
 * median / p95 of [RUNS] after [WARMUP]. Results: console and `build/perf-reports/attribute-storage.csv`.
 *
 * ```
 * ./gradlew :modules:epistola-core:perfTest --rerun --tests "*AttributeStoragePerfTest"
 * ```
 */
@Tag("perf")
@Tag("integration")
class AttributeStoragePerfTest : IntegrationTestBase() {

    private data class Result(val operation: String, val coldMs: Double, val medianMs: Double, val p95Ms: Double)

    @Test
    fun `attribute reads, writes and in-use checks on a large data set`() {
        val tenant = createTenant("Attribute perf").id
        val shared = testCatalog(tenant, CatalogKey.of("cat-0"))
        withMediator {
            for (c in 0 until CATALOGS) {
                val catalog = testCatalog(tenant, CatalogKey.of("cat-$c"))
                CreateCatalog(tenantKey = tenant, id = catalog.key, name = "Catalog $c").execute()
                repeat(LISTS) { l -> CreateCodeList(CodeListId(CodeListKey.of("list-$l"), catalog), "List $c/$l", sourceType = CodeListSource.INLINE, entries = (0 until 50).map { CodeListEntry("c$it", "Entry $it", it) }).execute() }
                repeat(ATTRIBUTES) { a ->
                    val id = AttributeId(AttributeKey.of("attr-$a"), catalog)
                    when (a % 3) {
                        0 -> CreateAttributeDefinition(id, "Attribute $c/$a", codeListId = CodeListId(CodeListKey.of("list-${a % LISTS}"), catalog)).execute()
                        1 -> if (c == 0) CreateAttributeDefinition(id, "Attribute $c/$a", allowedValues = listOf("x", "y")).execute() else CreateAttributeDefinition(id, "Attribute $c/$a", codeListId = CodeListId(CodeListKey.of("list-${a % LISTS}"), shared)).execute()
                        else -> CreateAttributeDefinition(id, "Attribute $c/$a", allowedValues = listOf("a", "b", "c")).execute()
                    }
                }
                mediator.releaseNext(catalog)
            }
        }
        val catalog = testCatalog(tenant, CatalogKey.of("cat-${CATALOGS / 2}"))
        val one = AttributeId(AttributeKey.of("attr-0"), catalog)
        val boundList = CodeListId(CodeListKey.of("list-0"), catalog)
        var flip = 0
        val results = listOf(
            measure("list a tenant's attributes") { ListAttributeDefinitions(TenantId(tenant)).query() },
            measure("list a catalog's attributes") { ListAttributeDefinitions(TenantId(tenant), catalog = catalog).query() },
            measure("read one attribute") { GetAttributeDefinition(one).query() },
            measure("save an attribute") { UpdateAttributeDefinition(one, "Attribute ${flip++}", codeListId = boundList).execute() },
            measure("validate variant attributes (3)") {
                validateAttributes(TenantId(tenant), mapOf("${catalog.key.value}.attr-0" to "c1", "${catalog.key.value}.attr-1" to "c2", "${catalog.key.value}.attr-2" to "a"))
            },
            measure("delete a bound code list (refused)", runs = 100) { runCatching { DeleteCodeList(boundList).execute() } },
        )
        val report = buildString {
            appendLine("operation,cold_ms,median_ms,p95_ms")
            results.forEach { appendLine("${it.operation},${"%.2f".format(it.coldMs)},${"%.2f".format(it.medianMs)},${"%.2f".format(it.p95Ms)}") }
        }
        println("Attribute storage perf\n$report")
        val out = Path.of("build/perf-reports/attribute-storage.csv")
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
        const val LISTS = 5
        const val ATTRIBUTES = 15
        const val RUNS = 200
        const val WARMUP = 20
    }
}
