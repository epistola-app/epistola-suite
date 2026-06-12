package app.epistola.suite.generation.collect

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.generation.collect.queries.FetchGenerationResults
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * End-to-end check that a successful document generation produces a row in
 * `generation_results` at the partition computed from the request's
 * routingKey (or its requestId fallback). Exercises the full path:
 * `GenerateDocument` command → request inserted → executor (fake) picks it up
 * and renders → `EmitGenerationResult` dispatched → row visible via the
 * `FetchGenerationResults` query.
 */
@Timeout(30)
class EmitOnExecutorIT : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()

    @Test
    fun `successful generation emits a row at the partition derived from routingKey`() = scenario {
        given {
            val tenant = tenant("Test Tenant")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "Invoice Template")
            val templateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(templateId, "Default")
            val variantId = VariantId(variant.id, templateId)
            val templateModel = TestTemplateBuilder.buildMinimal(name = "Invoice Template")
            val version = version(variantId, templateModel)
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            val data: ObjectNode = objectMapper.createObjectNode().apply {
                putObject("customer").put("name", "Jane")
                put("amount", 42)
            }
            execute(
                GenerateDocument(
                    tenantId = setup.tenant.id,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    versionId = setup.version.id,
                    environmentId = null,
                    data = data,
                    filename = "x.pdf",
                    routingKey = "order-9999",
                ),
            )
        }.then { setup, request ->
            // Drain the tenant's pending generation jobs synchronously
            drainGenerationJobs(setup.tenant.id)
            val job = withMediator { mediator.query(GetGenerationJob(setup.tenant.id, request.id))!! }
            assertThat(job.request.status).isEqualTo(RequestStatus.COMPLETED)

            val expectedPartition = Partition.partitionFor("order-9999")
            val page = withMediator {
                FetchGenerationResults(
                    tenantId = setup.tenant.id,
                    consumerId = "test-consumer-${UUID.randomUUID()}",
                    partitions = setOf(expectedPartition),
                    limit = 100,
                ).query()
            }
            val row = page.rows.firstOrNull { it.requestId == request.id }
            assertThat(row).`as`("a generation_results row should exist for request %s", request.id.value).isNotNull
            assertThat(row!!.partition).isEqualTo(expectedPartition)
            assertThat(row.routingKey).isEqualTo("order-9999")
            assertThat(row.status).isEqualTo(ResultStatus.COMPLETED)
            assertThat(row.documentId).isNotNull
        }
    }

    @Test
    fun `generation without routingKey falls back to requestId for partitioning`() = scenario {
        given {
            val tenant = tenant("Test Tenant")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "Invoice Template")
            val templateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(templateId, "Default")
            val variantId = VariantId(variant.id, templateId)
            val templateModel = TestTemplateBuilder.buildMinimal(name = "Invoice Template")
            val version = version(variantId, templateModel)
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            val data: ObjectNode = objectMapper.createObjectNode().apply {
                putObject("customer").put("name", "Jane")
            }
            execute(
                GenerateDocument(
                    tenantId = setup.tenant.id,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    versionId = setup.version.id,
                    environmentId = null,
                    data = data,
                    filename = "y.pdf",
                    routingKey = null,
                ),
            )
        }.then { setup, request ->
            drainGenerationJobs(setup.tenant.id)
            val job = withMediator { mediator.query(GetGenerationJob(setup.tenant.id, request.id))!! }
            assertThat(job.request.status).isEqualTo(RequestStatus.COMPLETED)

            val expectedPartition = Partition.partitionFor(request.id.value.toString())
            val page = withMediator {
                FetchGenerationResults(
                    tenantId = setup.tenant.id,
                    consumerId = "test-consumer-${UUID.randomUUID()}",
                    partitions = setOf(expectedPartition),
                    limit = 100,
                ).query()
            }
            val row = page.rows.firstOrNull { it.requestId == request.id }
            assertThat(row).isNotNull
            assertThat(row!!.routingKey).isEqualTo(request.id.value.toString())
            assertThat(row.partition).isEqualTo(expectedPartition)
        }
    }
}
