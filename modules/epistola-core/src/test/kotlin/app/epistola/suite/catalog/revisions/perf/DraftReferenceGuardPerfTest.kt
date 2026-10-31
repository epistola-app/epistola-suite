// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions.perf

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * What the save guard ([app.epistola.suite.catalog.revisions.DraftReferenceGuard]) costs: it reads
 * a version's unresolved references when a transaction first writes it and again before it commits,
 * so a save pays for two of these reads on top of its own work.
 *
 * Data set, at each of [SIZES]: a tenant with `shared` (released once) holding [SHARED_STENCILS]
 * stencils and `letters` holding [LOCAL_STENCILS] stencils and N templates of [VARIANTS] variants.
 * Every variant uses [USES] local and [USES] shared stencils, each variant's content distinct. The
 * report gives the read alone and a whole save, cold and median / p95 of [RUNS] after [WARMUP].
 * Results: console and `build/perf-reports/draft-reference-guard.csv`.
 *
 * ```
 * ./gradlew :modules:epistola-core:perfTest --rerun --tests "*DraftReferenceGuardPerfTest"
 * ```
 */
@Tag("perf")
@Tag("integration")
class DraftReferenceGuardPerfTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    private data class Result(val size: Int, val operation: String, val coldMs: Double, val medianMs: Double, val p95Ms: Double)

    @Test
    fun `the save guard on a large catalog`() {
        val results = SIZES.flatMap { measureAt(it) }
        val report = buildString {
            appendLine("templates,operation,cold_ms,median_ms,p95_ms")
            results.forEach { appendLine("${it.size},${it.operation},${"%.2f".format(it.coldMs)},${"%.2f".format(it.medianMs)},${"%.2f".format(it.p95Ms)}") }
        }
        println("Draft reference guard perf\n$report")
        val out = Path.of("build/perf-reports/draft-reference-guard.csv")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
    }

    private fun measureAt(templates: Int): List<Result> {
        val built = System.nanoTime()
        val tenant = createTenant("Guard perf $templates").id
        val shared = testCatalog(tenant, CatalogKey.of("shared"))
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        withMediator {
            CreateCatalog(tenantKey = tenant, id = shared.key, name = "Shared").execute()
            repeat(SHARED_STENCILS) { CreateStencil(StencilId(StencilKey.of("shared-$it"), shared), "Shared $it").execute() }
            mediator.releaseNext(shared)
            CreateCatalog(tenantKey = tenant, id = letters.key, name = "Letters").execute()
            repeat(LOCAL_STENCILS) { CreateStencil(StencilId(StencilKey.of("local-$it"), letters), "Local $it").execute() }
            for (t in 0 until templates) {
                val template = TemplateId(TemplateKey.of("tpl-$t"), letters)
                CreateDocumentTemplate(id = template, name = "Template $t").execute()
                repeat(VARIANTS - 1) { v -> CreateVariant(VariantId(VariantKey.of("v-$v"), template), "Variant $v", null, mapOf()).execute() }
                variantsOf(template).forEachIndexed { v, variant -> UpdateVariantContent(variant, using("$t-$v", t + v)).execute() }
            }
        }
        val version = draftOf(tenant)
        val (rows, references) = sizeOf(tenant, version)
        println("Guard perf at $templates templates: $rows resources, $references references in the draft, built in ${(System.nanoTime() - built) / 1_000_000_000} s")

        var saves = 0
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("tpl-${templates / 2}"), letters))
        val model = withMediator { GetVariantContent(variant).query()!! }
        val unresolved = jdbi.withHandle<Set<DraftResources.Unresolved>, Exception> { DraftResources.unresolved(it, tenant, version) }
        assertThat(unresolved).`as`("a catalog built by commands resolves everything").isEmpty()

        return listOf(
            measure(templates, "read unresolved references") {
                jdbi.useHandle<Exception> { DraftResources.unresolved(it, tenant, version) }
            },
            measure(templates, "save a variant's model (guard included)") {
                // A save that changes nothing writes nothing, and so is not guarded: each one differs.
                val edited = model.copy(documentStylesOverride = mapOf("color" to "#%06x".format(++saves)))
                withMediator { UpdateVariantContent(variant, edited).execute() }
            },
        )
    }

    private fun variantsOf(template: TemplateId): List<VariantId> = listOf(VariantId(VariantKey.INITIAL, template)) +
        (0 until VARIANTS - 1).map { VariantId(VariantKey.of("v-$it"), template) }

    /** A model using [USES] local and [USES] shared stencils, its node ids making each variant's content its own. */
    private fun using(tag: String, offset: Int): TemplateDocument {
        val instances = (0 until USES).flatMap { i ->
            listOf(
                Triple("l-$tag-$i", "local-${(offset + i) % LOCAL_STENCILS}", "letters"),
                Triple("s-$tag-$i", "shared-${(offset + i) % SHARED_STENCILS}", "shared"),
            )
        }
        return TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot"))) +
                instances.associate { (id, stencil, catalog) ->
                    id to Node(id = id, type = "stencil", props = mapOf("stencilKey" to stencil, "catalogKey" to catalog))
                },
            slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = instances.map { it.first })),
            themeRef = ThemeRef.Inherit,
        )
    }

    private fun draftOf(tenant: TenantKey): UUID = jdbi.withHandle<UUID, Exception> {
        it.createQuery("SELECT next_version_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters'")
            .bind("t", tenant).mapTo(UUID::class.java).one()
    }

    private fun sizeOf(tenant: TenantKey, version: UUID): Pair<Long, Long> = jdbi.withHandle<Pair<Long, Long>, Exception> {
        val rows = it.createQuery("SELECT count(*) FROM release_resources WHERE tenant_key = :t AND version_id = :v")
            .bind("t", tenant).bind("v", version).mapTo(Long::class.java).one()
        val references = it.createQuery(
            "SELECT count(*) FROM release_resources r JOIN resource_references x ON x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash WHERE r.tenant_key = :t AND r.version_id = :v",
        ).bind("t", tenant).bind("v", version).mapTo(Long::class.java).one()
        rows to references
    }

    private fun measure(size: Int, operation: String, block: () -> Unit): Result {
        val cold = timed(block)
        repeat(WARMUP) { block() }
        val samples = (1..RUNS).map { timed(block) }.sorted()
        return Result(size, operation, cold, samples[samples.size / 2], samples[(samples.size * 95) / 100 - 1])
    }

    private fun timed(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1e6
    }

    private companion object {
        val SIZES = listOf(50, 500, 1000)
        const val VARIANTS = 2
        const val USES = 5
        const val LOCAL_STENCILS = 50
        const val SHARED_STENCILS = 50
        const val RUNS = 100
        const val WARMUP = 10
    }
}
