// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.perf

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.queries.CountDocumentTemplates
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListTemplateSummaries
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * How long the template reads take, before and after they moved behind `current_templates` and
 * `current_template_variants` (ADR 0033, step 7a). The same file runs on both, so it uses only
 * messages both have.
 *
 * Data set: one tenant with [CATALOGS] catalogs of [TEMPLATES] templates of [VARIANTS] variants
 * each, every catalog released once. Cold = first call; median / p95 of [RUNS] after [WARMUP].
 * Results: console and `build/perf-reports/template-reads.csv`.
 *
 * ```
 * ./gradlew :modules:epistola-core:perfTest --rerun --tests "*TemplateReadsPerfTest"
 * ```
 */
@Tag("perf")
@Tag("integration")
class TemplateReadsPerfTest : IntegrationTestBase() {

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    private data class Result(val operation: String, val coldMs: Double, val medianMs: Double, val p95Ms: Double)

    @Test
    fun `template reads on a large data set`() {
        val tenant = createTenant("Template perf").id
        withMediator {
            for (c in 0 until CATALOGS) {
                val catalog = testCatalog(tenant, CatalogKey.of("cat-$c"))
                CreateCatalog(tenantKey = tenant, id = catalog.key, name = "Catalog $c").execute()
                repeat(TEMPLATES) { t ->
                    val template = TemplateId(TemplateKey.of("tpl-$c-$t"), catalog)
                    CreateDocumentTemplate(id = template, name = "Template $c/$t").execute()
                    repeat(VARIANTS - 1) { v -> CreateVariant(VariantId(VariantKey.of("v-$v"), template), "Variant $v", null, mapOf()).execute() }
                }
                mediator.releaseNext(catalog)
            }
        }
        val catalog = testCatalog(tenant, CatalogKey.of("cat-${CATALOGS / 2}"))
        val template = TemplateId(TemplateKey.of("tpl-${CATALOGS / 2}-0"), catalog)
        val variant = VariantId(VariantKey.of("v-0"), template)
        val model = withMediator { GetVariantContent(variant).query()!! }
        val results = listOf(
            measure("list a catalog's templates (page of 50)") { ListDocumentTemplates(TenantId(tenant), catalog = catalog, limit = 50).query() },
            measure("count a catalog's templates") { CountDocumentTemplates(TenantId(tenant), catalog = catalog).query() },
            measure("list a tenant's template summaries (page of 50)") { ListTemplateSummaries(TenantId(tenant), limit = 50).query() },
            measure("read one template") { GetDocumentTemplate(template).query() },
            measure("list a template's variants") { ListVariants(template).query() },
            measure("read a variant's model") { GetVariantContent(variant).query() },
            measure("read a template's data contract") { GetDataContract(template).query() },
            measure("open the editor") { GetEditorContext(variant).query() },
            measure("save a variant's model", runs = 100, warmup = 10) { UpdateVariantContent(variant, model).execute() },
        )

        // Autosave churn (ADR 0033, step 7b): what one editing session of 20 distinct saves leaves
        // behind in resource_contents until the content collector takes what nothing names.
        fun contents(): Long = jdbi.withHandle<Long, Exception> { it.createQuery("SELECT count(*) FROM resource_contents WHERE tenant_key = :t").bind("t", tenant).mapTo(Long::class.java).one() }
        val before = contents()
        withMediator {
            repeat(20) { i ->
                val edited = model.copy(documentStylesOverride = mapOf("color" to "#0000%02x".format(i)))
                UpdateVariantContent(variant, edited).execute()
            }
        }
        val churn = contents() - before
        println("Template autosave churn: $churn new content rows for 20 saves of one variant")
        val report = buildString {
            appendLine("operation,cold_ms,median_ms,p95_ms")
            appendLine("autosave churn (content rows per 20 saves),$churn,,")
            results.forEach { appendLine("${it.operation},${"%.2f".format(it.coldMs)},${"%.2f".format(it.medianMs)},${"%.2f".format(it.p95Ms)}") }
        }
        println("Template reads perf\n$report")
        val out = Path.of("build/perf-reports/template-reads.csv")
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
        const val TEMPLATES = 25
        const val VARIANTS = 3
        const val RUNS = 200
        const val WARMUP = 20
    }
}
