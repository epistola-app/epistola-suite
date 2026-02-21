package app.epistola.suite.documents.queries

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

class DocumentQueriesTest : CoreIntegrationTestBase() {
    private val objectMapper = ObjectMapper()

    @Autowired
    private lateinit var contentStore: ContentStore

    @Test
    fun `GetGenerationJob returns job with items`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Test Template",
        )
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        val request = mediator.send(
            GenerateDocument(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value"),
                filename = "test.pdf",
            ),
        )

        val job = mediator.query(GetGenerationJob(tenant.id, request.id))

        assertThat(job).isNotNull
        assertThat(job!!.request.id).isEqualTo(request.id)
        assertThat(job.items).hasSize(1)
        assertThat(job.items[0].templateId).isEqualTo(template.id)
        assertThat(job.items[0].variantId).isEqualTo(variant.id)
        assertThat(job.items[0].versionId).isEqualTo(version.id)
    }

    @Test
    fun `GetGenerationJob returns null for non-existent job`() {
        val tenant = createTenant("Test Tenant")
        val randomId = GenerationRequestId.generate()

        val job = mediator.query(GetGenerationJob(tenant.id, randomId))

        assertThat(job).isNull()
    }

    @Test
    fun `GetGenerationJob returns null for job from different tenant`() {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant1.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant1.id, templateId = template.id, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Test Template",
        )
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant1.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        val request = mediator.send(
            GenerateDocument(
                tenantId = tenant1.id,
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value"),
                filename = "test.pdf",
            ),
        )

        val job = mediator.query(GetGenerationJob(tenant2.id, request.id))

        assertThat(job).isNull()
    }

    @Test
    fun `ListGenerationJobs filters by status`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Test Template",
        )
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        // Create multiple requests
        (1..3).forEach { i ->
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = template.id,
                    variantId = variant.id,
                    versionId = version.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode().put("test", i),
                    filename = "test-$i.pdf",
                ),
            )
        }

        // Wait for at least one to complete
        await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until {
                val jobs = mediator.query(ListGenerationJobs(tenant.id, RequestStatus.COMPLETED, 10, 0))
                jobs.isNotEmpty()
            }

        // List all jobs
        val allJobs = mediator.query(ListGenerationJobs(tenant.id, null, 10, 0))
        assertThat(allJobs).hasSizeGreaterThanOrEqualTo(3)

        // List only completed
        val completedJobs = mediator.query(ListGenerationJobs(tenant.id, RequestStatus.COMPLETED, 10, 0))
        assertThat(completedJobs).isNotEmpty
        assertThat(completedJobs).allMatch { it.status == RequestStatus.COMPLETED }
    }

    @Test
    fun `ListGenerationJobs respects pagination`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Test Template",
        )
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        // Create 5 requests
        (1..5).forEach { i ->
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = template.id,
                    variantId = variant.id,
                    versionId = version.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode().put("test", i),
                    filename = "test-$i.pdf",
                ),
            )
        }

        // Wait briefly for jobs to be created
        Thread.sleep(500)

        // Get first page (limit 2)
        val page1 = mediator.query(ListGenerationJobs(tenant.id, null, 2, 0))
        assertThat(page1).hasSize(2)

        // Get second page
        val page2 = mediator.query(ListGenerationJobs(tenant.id, null, 2, 2))
        assertThat(page2).hasSize(2)

        // Verify different jobs
        val page1Ids = page1.map { it.id }.toSet()
        val page2Ids = page2.map { it.id }.toSet()
        assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids)
    }

    @Test
    fun `GetDocument returns document with content`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Test Template",
        )
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        val request = mediator.send(
            GenerateDocument(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value"),
                filename = "test.pdf",
            ),
        )

        await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
            }

        val job = mediator.query(GetGenerationJob(tenant.id, request.id))!!
        val documentId = job.items[0].documentId!!

        val document = mediator.query(GetDocument(tenant.id, documentId))

        assertThat(document).isNotNull
        assertThat(document!!.id).isEqualTo(documentId)
        assertThat(document.filename).isEqualTo("test.pdf")
        assertThat(document.contentType).isEqualTo("application/pdf")
        assertThat(document.sizeBytes).isGreaterThan(0)

        // Verify content is stored in ContentStore
        val stored = contentStore.get(ContentKey.document(tenant.id, documentId))
        assertThat(stored).isNotNull
        val contentBytes = stored!!.content.readAllBytes()
        assertThat(contentBytes).isNotEmpty()
        // Verify it's a PDF by checking the magic bytes (%PDF)
        assertThat(contentBytes.take(4).toByteArray()).isEqualTo(byteArrayOf(0x25, 0x50, 0x44, 0x46))
    }

    @Test
    fun `GetDocument returns null for non-existent document`() {
        val tenant = createTenant("Test Tenant")

        val document = mediator.query(GetDocument(tenant.id, DocumentId.generate()))

        assertThat(document).isNull()
    }

    @Test
    fun `ListDocuments filters by template`() {
        val tenant = createTenant("Test Tenant")
        val template1 = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Template 1"))
        val template2 = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Template 2"))

        val variant1 = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template1.id, title = "Default", description = null, attributes = emptyMap()))!!
        val variant2 = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template2.id, title = "Default", description = null, attributes = emptyMap()))!!

        val templateModel1 = TestTemplateBuilder.buildMinimal(
            name = "Template 1",
        )
        val templateModel2 = TestTemplateBuilder.buildMinimal(
            name = "Template 2",
        )
        val version1 = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template1.id,
                variantId = variant1.id,
                templateModel = templateModel1,
            ),
        )!!
        val version2 = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template2.id,
                variantId = variant2.id,
                templateModel = templateModel2,
            ),
        )!!

        // Generate documents for template1 and track request IDs
        val requests = mutableListOf<GenerationRequestId>()
        (1..2).forEach { i ->
            val request = mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = template1.id,
                    variantId = variant1.id,
                    versionId = version1.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode().put("test", i),
                    filename = "template1-$i.pdf",
                ),
            )
            requests.add(request.id)
        }

        // Generate document for template2
        val request3 = mediator.send(
            GenerateDocument(
                tenantId = tenant.id,
                templateId = template2.id,
                variantId = variant2.id,
                versionId = version2.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value"),
                filename = "template2.pdf",
            ),
        )
        requests.add(request3.id)

        // Wait for all specific jobs to complete
        await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(200, TimeUnit.MILLISECONDS)
            .until {
                requests.all { requestId ->
                    val job = mediator.query(GetGenerationJob(tenant.id, requestId))
                    job?.request?.status == RequestStatus.COMPLETED
                }
            }

        // List all documents
        val allDocs = mediator.query(ListDocuments(tenantId = tenant.id, limit = 10))
        assertThat(allDocs).hasSizeGreaterThanOrEqualTo(3)

        // List only template1 documents
        val template1Docs = mediator.query(ListDocuments(tenantId = tenant.id, templateId = template1.id, limit = 10))
        assertThat(template1Docs).hasSizeGreaterThanOrEqualTo(2)
        assertThat(template1Docs).allMatch { it.templateId == template1.id }
    }
}
