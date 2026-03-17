package app.epistola.suite.documents.services

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.documents.model.AssemblyStatus
import app.epistola.suite.documents.model.BatchDownloadFormat
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

@Timeout(60)
class BatchAssemblyServiceTest : CoreIntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var contentStore: ContentStore

    @Autowired
    private lateinit var batchAssemblyService: BatchAssemblyService

    private val objectMapper = ObjectMapper()

    private fun createBatchWithDownloadFormats(
        formats: List<BatchDownloadFormat>,
        itemCount: Int = 3,
    ): Pair<BatchKey, app.epistola.suite.common.ids.TenantKey> {
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
        return batchId to tenant.id
    }

    private fun awaitBatchCompletion(batchId: BatchKey) {
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
    }

    @Test
    fun `assembles ZIP from completed batch`() {
        val (batchId, tenantKey) = createBatchWithDownloadFormats(listOf(BatchDownloadFormat.ZIP))
        awaitBatchCompletion(batchId)

        // Trigger assembly manually (in real flow it's triggered by finalizeBatchIfComplete)
        batchAssemblyService.assembleDownloads(tenantKey, batchId)

        // Wait for assembly to complete (it's @Async but we called it directly, so it's synchronous)
        val status = jdbi.withHandle<String, Exception> { handle ->
            handle.createQuery(
                "SELECT assembly_status FROM document_generation_batches WHERE id = :id",
            )
                .bind("id", batchId)
                .mapTo(String::class.java)
                .one()
        }
        assertThat(status).isEqualTo(AssemblyStatus.COMPLETED.name)

        // Verify ZIP exists in ContentStore
        val zipKey = ContentKey.batchDownload(tenantKey, batchId, "ZIP", 1)
        val stored = contentStore.get(zipKey)
        assertThat(stored).isNotNull
        assertThat(stored!!.contentType).isEqualTo("application/zip")

        // Verify ZIP contains expected files
        val zipEntries = mutableListOf<String>()
        ZipInputStream(stored.content).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                zipEntries.add(entry.name)
                entry = zis.nextEntry
            }
        }
        assertThat(zipEntries).containsExactly("doc-1.pdf", "doc-2.pdf", "doc-3.pdf")
    }

    @Test
    fun `assembles merged PDF from completed batch`() {
        val (batchId, tenantKey) = createBatchWithDownloadFormats(listOf(BatchDownloadFormat.MERGED_PDF))
        awaitBatchCompletion(batchId)

        batchAssemblyService.assembleDownloads(tenantKey, batchId)

        val status = jdbi.withHandle<String, Exception> { handle ->
            handle.createQuery(
                "SELECT assembly_status FROM document_generation_batches WHERE id = :id",
            )
                .bind("id", batchId)
                .mapTo(String::class.java)
                .one()
        }
        assertThat(status).isEqualTo(AssemblyStatus.COMPLETED.name)

        // Verify merged PDF exists in ContentStore
        val pdfKey = ContentKey.batchDownload(tenantKey, batchId, "MERGED_PDF", 1)
        val stored = contentStore.get(pdfKey)
        assertThat(stored).isNotNull
        assertThat(stored!!.contentType).isEqualTo("application/pdf")
        assertThat(stored.sizeBytes).isGreaterThan(0)
    }

    @Test
    fun `assembles both ZIP and merged PDF`() {
        val (batchId, tenantKey) = createBatchWithDownloadFormats(
            listOf(BatchDownloadFormat.ZIP, BatchDownloadFormat.MERGED_PDF),
        )
        awaitBatchCompletion(batchId)

        batchAssemblyService.assembleDownloads(tenantKey, batchId)

        val status = jdbi.withHandle<String, Exception> { handle ->
            handle.createQuery(
                "SELECT assembly_status FROM document_generation_batches WHERE id = :id",
            )
                .bind("id", batchId)
                .mapTo(String::class.java)
                .one()
        }
        assertThat(status).isEqualTo(AssemblyStatus.COMPLETED.name)

        // Both formats should exist
        assertThat(contentStore.get(ContentKey.batchDownload(tenantKey, batchId, "ZIP", 1))).isNotNull
        assertThat(contentStore.get(ContentKey.batchDownload(tenantKey, batchId, "MERGED_PDF", 1))).isNotNull
    }

    @Test
    fun `stores download part metadata on batch record`() {
        val (batchId, tenantKey) = createBatchWithDownloadFormats(listOf(BatchDownloadFormat.ZIP))
        awaitBatchCompletion(batchId)

        batchAssemblyService.assembleDownloads(tenantKey, batchId)

        val downloadParts = jdbi.withHandle<String, Exception> { handle ->
            handle.createQuery(
                "SELECT download_parts FROM document_generation_batches WHERE id = :id",
            )
                .bind("id", batchId)
                .mapTo(String::class.java)
                .one()
        }

        assertThat(downloadParts).contains("ZIP")
        assertThat(downloadParts).contains("partNumber")
        assertThat(downloadParts).contains("sizeBytes")
    }

    @Test
    fun `preserves sequence order in assembled files`() {
        val (batchId, tenantKey) = createBatchWithDownloadFormats(listOf(BatchDownloadFormat.ZIP), itemCount = 5)
        awaitBatchCompletion(batchId)

        batchAssemblyService.assembleDownloads(tenantKey, batchId)

        val zipKey = ContentKey.batchDownload(tenantKey, batchId, "ZIP", 1)
        val stored = contentStore.get(zipKey)
        assertThat(stored).isNotNull

        val zipEntries = mutableListOf<String>()
        ZipInputStream(stored!!.content).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                zipEntries.add(entry.name)
                entry = zis.nextEntry
            }
        }
        assertThat(zipEntries).containsExactly("doc-1.pdf", "doc-2.pdf", "doc-3.pdf", "doc-4.pdf", "doc-5.pdf")
    }
}
