// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.statistics

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.DeleteDocument
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * Generation statistics: counted from the generation results in the background, so a
 * deleted document still counts, each result once; previews from memory; hours folded into days.
 */
@Isolated("The roll-up reads every tenant's generation results")
class GenerationStatisticsIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var previews: PreviewStatisticsCounter

    /** Without the settle delay, so a result just written is counted in the same test. */
    private val rollup by lazy { GenerationStatisticsRollup(jdbi, StatisticsProperties(settle = Duration.ZERO)) }

    private fun today() = EpistolaClock.localDate()

    private fun statistics(tenant: TenantKey) = withMediator { GetGenerationStatistics(tenant, today().minusDays(30), today().plusDays(1)).query() }

    /** A tenant with a released template; generates [documents] documents and returns their keys. */
    private fun generating(name: String, documents: Int, deleteThem: Boolean = false, previewsToo: Int = 0): TenantKey = scenario {
        given {
            val tenant = tenant(name)
            val template = template(tenant.id, "Letter")
            val templateId = TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))
            val variant = variant(templateId, "Default")
            released(VariantId(variant.id, templateId), TestTemplateBuilder.buildMinimal(name = "Letter"))
            Triple(tenant.id, template.id, variant.id)
        }.whenever { (tenant, template, variant) ->
            val data = objectMapper.createObjectNode().put("name", "Jan")
            val requests = (1..documents).map {
                execute(GenerateDocument(catalog = testCatalog(tenant, CatalogKey.DEFAULT), templateId = template, variantId = variant, environmentId = null, data = data, filename = "a$it.pdf"))
            }
            drainGenerationJobs(tenant)
            if (deleteThem) {
                requests.forEach { request ->
                    val document = GetGenerationJob(tenant, request.id).query()!!.items.single().documentKey!!
                    DeleteDocument(tenant, document).execute()
                }
            }
            repeat(previewsToo) {
                PreviewDocument(catalog = testCatalog(tenant, CatalogKey.DEFAULT), templateId = template, data = data, variantId = variant, catalogVersion = null).query()
            }
            tenant
        }.result
    }

    @Test
    fun `a generated document counts once, also after it was deleted`() {
        val tenant = generating("Statistics Deleted", documents = 2, deleteThem = true)

        rollup.rollUp()
        rollup.rollUp()

        val statistics = statistics(tenant)
        assertThat(statistics.completed).`as`("both documents, deleted or not, counted once").isEqualTo(2)
        assertThat(statistics.failed).isZero()
        assertThat(statistics.completeUntil).isNotNull()
    }

    @Test
    fun `previews are counted in memory and written when flushed`() {
        val tenant = generating("Statistics Previews", documents = 0, previewsToo = 3)

        assertThat(statistics(tenant).previews).`as`("not written before the flush").isZero()
        previews.flush()

        assertThat(statistics(tenant).previews).isEqualTo(3)
    }

    @Test
    fun `hours older than the hourly retention are folded into days, and the figures stay the same`() {
        val tenant = generating("Statistics Folded", documents = 1)
        rollup.rollUp()
        val before = statistics(tenant)

        rollup.fold(EpistolaClock.instant().plus(Duration.ofDays(9)))

        assertThat(statistics(tenant).completed).isEqualTo(before.completed).isEqualTo(1)
        val hours = jdbi.withHandle<Int, Exception> { it.createQuery("SELECT COUNT(*) FROM generation_stats_hourly WHERE tenant_key = :t").bind("t", tenant.value).mapTo(Int::class.java).one() }
        assertThat(hours).`as`("the hour went into a day").isZero()
    }

    @Test
    fun `a result that settles after one with a higher sequence is still counted`() {
        rollup.rollUp()
        val tenant = generating("Statistics Out Of Order", documents = 2)
        val sequences = jdbi.withHandle<List<Long>, Exception> {
            it.createQuery("SELECT sequence FROM generation_results WHERE tenant_key = :t ORDER BY sequence").bind("t", tenant.value).mapTo(Long::class.java).list()
        }
        // The lower sequence is the younger one (its transaction started later), the higher one has settled.
        jdbi.useHandle<Exception> {
            it.createUpdate("UPDATE generation_results SET created_at = NOW() - INTERVAL '45 minutes' WHERE sequence = :s").bind("s", sequences.last()).execute()
        }
        val settling = GenerationStatisticsRollup(jdbi, StatisticsProperties(settle = Duration.ofMinutes(30)))

        settling.rollUp()
        assertThat(statistics(tenant).completed).`as`("nothing past the result still settling").isZero()

        jdbi.useHandle<Exception> {
            it.createUpdate("UPDATE generation_results SET created_at = NOW() - INTERVAL '45 minutes' WHERE sequence = :s").bind("s", sequences.first()).execute()
        }
        settling.rollUp()
        assertThat(statistics(tenant).completed).`as`("both, once each").isEqualTo(2)
    }

    @Test
    fun `with statistics off, previews are not counted`() {
        val off = PreviewStatisticsCounter(jdbi, StatisticsProperties(enabled = false))
        val tenant = generating("Statistics Off", documents = 0)

        off.onQuery(PreviewDocument(catalog = testCatalog(tenant, CatalogKey.DEFAULT), templateId = TemplateKey.of("letter"), data = objectMapper.createObjectNode(), variantId = null, catalogVersion = null), app.epistola.suite.mediator.DispatchOutcome.SUCCESS, null)
        off.flush()

        assertThat(statistics(tenant).previews).isZero()
    }
}
