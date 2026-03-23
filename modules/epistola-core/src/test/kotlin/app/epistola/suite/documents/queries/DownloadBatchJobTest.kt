package app.epistola.suite.documents.queries

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.documents.model.BatchDownloadFormat
import app.epistola.suite.documents.services.BatchAssemblyService
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

@Timeout(60)
class DownloadBatchJobTest : CoreIntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var batchAssemblyService: BatchAssemblyService

    private val objectMapper = ObjectMapper()

    private fun createAndCompleteBatch(
        formats: List<BatchDownloadFormat> = listOf(BatchDownloadFormat.ZIP),
        itemCount: Int = 3,
    ): Pair<BatchKey, TenantKey> = withAuthentication {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
        mediator.send(CreateDocumentTemplate(id = templateId, name = "Test Template"))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = mediator.send(
            CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()),
        )!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
        val version = mediator.send(UpdateDraft(variantId = variantId, templateModel = templateModel))!!

        val items = (1..itemCount).map { i ->
            BatchGenerationItem(
                templateId = templateId.key,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", i),
                filename = "doc-$i.pdf",
            )
        }

        val batchId = mediator.send(GenerateDocumentBatch(tenant.id, items, formats))

        // Wait for batch to complete
        await().atMost(30, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS).untilAsserted {
            val completedAt = jdbi.withHandle<Any?, Exception> { handle ->
                handle.createQuery(
                    "SELECT completed_at FROM document_generation_batches WHERE id = :id",
                )
                    .bind("id", batchId)
                    .mapTo(java.time.OffsetDateTime::class.java)
                    .findOne()
                    .orElse(null)
            }
            assertThat(completedAt).isNotNull()
        }

        // Assemble downloads
        batchAssemblyService.assembleDownloads(tenant.id, batchId)

        batchId to tenant.id
    }

    @Test
    fun `downloads assembled ZIP`() {
        val (batchId, tenantKey) = createAndCompleteBatch(listOf(BatchDownloadFormat.ZIP))

        val result = withMediator { DownloadBatchJob(tenantKey, batchId, "zip", 1).query() }

        assertThat(result.contentType).isEqualTo("application/zip")
        assertThat(result.filename).contains("batch-")
        assertThat(result.filename).endsWith(".zip")
        assertThat(result.content.sizeBytes).isGreaterThan(0)
    }

    @Test
    fun `downloads assembled merged PDF`() {
        val (batchId, tenantKey) = createAndCompleteBatch(listOf(BatchDownloadFormat.MERGED_PDF))

        val result = withMediator { DownloadBatchJob(tenantKey, batchId, "merged_pdf", 1).query() }

        assertThat(result.contentType).isEqualTo("application/pdf")
        assertThat(result.filename).contains("batch-")
        assertThat(result.filename).contains("-merged")
        assertThat(result.filename).endsWith(".pdf")
        assertThat(result.content.sizeBytes).isGreaterThan(0)
    }

    @Test
    fun `returns 404 for non-existent batch`() {
        val tenant = createTenant("Test Tenant")

        assertThatThrownBy {
            withMediator { DownloadBatchJob(tenant.id, BatchKey.generate(), "zip", 1).query() }
        }.isInstanceOf(BatchDownloadException::class.java)
            .extracting("statusCode").isEqualTo(404)
    }

    @Test
    fun `returns 400 for format not requested at submission`() {
        // Submit batch with only ZIP format
        val (batchId, tenantKey) = createAndCompleteBatch(listOf(BatchDownloadFormat.ZIP))

        // Try to download merged_pdf which was not requested
        assertThatThrownBy {
            withMediator { DownloadBatchJob(tenantKey, batchId, "merged_pdf", 1).query() }
        }.isInstanceOf(BatchDownloadException::class.java)
            .extracting("statusCode").isEqualTo(400)
    }

    @Test
    fun `returns 400 for invalid format string`() {
        val tenant = createTenant("Test Tenant")

        assertThatThrownBy {
            withMediator { DownloadBatchJob(tenant.id, BatchKey.generate(), "invalid_format", 1).query() }
        }.isInstanceOf(BatchDownloadException::class.java)
            .extracting("statusCode").isEqualTo(400)
    }

    @Test
    fun `returns 409 for incomplete batch`() = withAuthentication {
        // Create a batch but don't wait for completion
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
        mediator.send(CreateDocumentTemplate(id = templateId, name = "Test Template"))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = mediator.send(
            CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()),
        )!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
        val version = mediator.send(UpdateDraft(variantId = variantId, templateModel = templateModel))!!

        val items = (1..50).map { i ->
            BatchGenerationItem(
                templateId = templateId.key,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", i),
                filename = "doc-$i.pdf",
            )
        }
        val batchId = mediator.send(GenerateDocumentBatch(tenant.id, items, listOf(BatchDownloadFormat.ZIP)))

        // Try downloading immediately (batch likely not complete)
        assertThatThrownBy {
            DownloadBatchJob(tenant.id, batchId, "zip", 1).query()
        }.isInstanceOf(BatchDownloadException::class.java)
            .satisfies({ ex ->
                val bde = ex as BatchDownloadException
                // Either 409 (not complete) or 404 (not found) depending on timing
                assertThat(bde.statusCode).isIn(404, 409)
            })
    }

    @Test
    fun `single-document optimization returns PDF directly`() {
        val (batchId, tenantKey) = createAndCompleteBatch(
            formats = listOf(BatchDownloadFormat.ZIP),
            itemCount = 1,
        )

        // Even though ZIP was requested, single-doc optimization returns PDF directly
        val result = withMediator { DownloadBatchJob(tenantKey, batchId, "zip", 1).query() }

        assertThat(result.contentType).isEqualTo("application/pdf")
    }
}
