// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.perf

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeListEntryHidden
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.CodeListEntryExists
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
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
 * How long the code list operations take, before and after code lists moved to JSON storage
 * (ADR 0033, step 2). The same file runs on both, so it uses only messages both have.
 *
 * Data set: one tenant with [CATALOGS] catalogs of [LISTS] code lists each, released once, plus one
 * catalog holding a list of 1,000 and one of 10,000 entries; measured on those two. Entries are
 * looked up by code from the middle of the list. Cold = first call; median / p95 of [RUNS] after
 * [WARMUP]. Results: console and `build/perf-reports/code-list-storage.csv`.
 *
 * ```
 * ./gradlew :modules:epistola-core:perfTest --rerun --tests "*CodeListStoragePerfTest"
 * ```
 */
@Tag("perf")
@Tag("integration")
class CodeListStoragePerfTest : IntegrationTestBase() {

    private data class Result(val operation: String, val coldMs: Double, val medianMs: Double, val p95Ms: Double)

    private fun entries(n: Int) = (0 until n).map { CodeListEntry(code = "C%05d".format(it), label = "Entry $it of $n", sortOrder = it, hidden = false) }

    @Test
    fun `code list reads and writes, at 1,000 and 10,000 entries`() {
        val tenant = createTenant("Code list perf").id
        withMediator {
            for (c in 0 until CATALOGS) {
                val catalog = testCatalog(tenant, CatalogKey.of("cat-$c"))
                CreateCatalog(tenantKey = tenant, id = catalog.key, name = "Catalog $c").execute()
                repeat(LISTS) { l -> CreateCodeList(CodeListId(CodeListKey.of("list-$l"), catalog), "List $c/$l", sourceType = CodeListSource.INLINE, entries = entries(20)).execute() }
                mediator.releaseNext(catalog)
            }
        }
        val big = testCatalog(tenant, CatalogKey.of("big"))
        withMediator {
            CreateCatalog(tenantKey = tenant, id = big.key, name = "Big").execute()
            for (n in SIZES) CreateCodeList(CodeListId(CodeListKey.of("list-$n"), big), "List of $n", sourceType = CodeListSource.INLINE, entries = entries(n)).execute()
        }

        val results = mutableListOf<Result>()
        results += measure("list a tenant's code lists") { ListCodeLists(TenantId(tenant)).query() }
        for (n in SIZES) {
            val id = CodeListId(CodeListKey.of("list-$n"), big)
            val middle = "C%05d".format(n / 2)
            var flip = false
            results += measure("$n: read the code list") { GetCodeList(id).query() }
            results += measure("$n: list visible entries") { ListCodeListEntries(id).query() }
            results += measure("$n: list all entries") { ListCodeListEntries(id, includeHidden = true).query() }
            results += measure("$n: entry exists") { CodeListEntryExists(big, id.key, middle).query() }
            results += measure("$n: entry does not exist") { CodeListEntryExists(big, id.key, "nope").query() }
            results += measure("$n: hide or show an entry", runs = 100) {
                flip = !flip
                UpdateCodeListEntryHidden(id, middle, flip).execute()
            }
        }

        val report = buildString {
            appendLine("operation,cold_ms,median_ms,p95_ms")
            results.forEach { appendLine("${it.operation},${"%.2f".format(it.coldMs)},${"%.2f".format(it.medianMs)},${"%.2f".format(it.p95Ms)}") }
        }
        println("Code list storage perf\n$report")
        val out = Path.of("build/perf-reports/code-list-storage.csv")
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
        const val LISTS = 10
        val SIZES = listOf(1_000, 10_000)
        const val RUNS = 200
        const val WARMUP = 20
    }
}
