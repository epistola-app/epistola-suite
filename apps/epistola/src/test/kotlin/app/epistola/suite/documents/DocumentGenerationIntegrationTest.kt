package app.epistola.suite.documents

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.BatchValidationException
import app.epistola.suite.documents.commands.CancelGenerationJob
import app.epistola.suite.documents.commands.DeleteDocument
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetDocument
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.ListDocuments
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.testing.DocumentSetup
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.TimeUnit

@Timeout(30) // All tests must complete within 30 seconds
class DocumentGenerationIntegrationTest : BaseIntegrationTest() {
    private val objectMapper = ObjectMapper()

    @Test
    fun `generate single document successfully`() = scenario {
        given {
            val tenant = tenant("Test Tenant")
            val template = template(tenant.id, "Invoice Template")
            val variant = variant(tenant.id, template.id, "Default")
            val templateModel = TestTemplateBuilder.buildMinimal(name = "Invoice Template")
            val version = version(tenant.id, template.id, variant.id, templateModel)
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            val data: ObjectNode = objectMapper.createObjectNode().apply {
                putObject("customer").put("name", "John Doe")
                put("amount", 1000.50)
            }
            execute(
                GenerateDocument(
                    tenantId = setup.tenant.id,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    versionId = setup.version.id,
                    environmentId = null,
                    data = data,
                    filename = "invoice-001.pdf",
                ),
            )
        }.then { setup, request ->
            // Verify request was created
            assertThat(request.id).isNotNull()
            assertThat(request.status).isIn(RequestStatus.PENDING, RequestStatus.IN_PROGRESS)
            assertThat(request.totalCount).isEqualTo(1)

            // Wait for job to complete
            await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted {
                    val job = mediator.query(GetGenerationJob(setup.tenant.id, request.id))
                    assertThat(job).isNotNull
                    assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
                }

            // Verify job result
            val job = mediator.query(GetGenerationJob(setup.tenant.id, request.id))!!
            assertThat(job.request.completedCount).isEqualTo(1)
            assertThat(job.request.failedCount).isEqualTo(0)
            assertThat(job.items).hasSize(1)

            val item = job.items[0]
            assertThat(item.status.name).isEqualTo("COMPLETED")
            assertThat(item.documentId).isNotNull()

            // Verify document was created
            val document = mediator.query(GetDocument(setup.tenant.id, item.documentId!!))!!
            assertThat(document.filename).isEqualTo("invoice-001.pdf")
            assertThat(document.contentType).isEqualTo("application/pdf")
            assertThat(document.sizeBytes).isGreaterThan(0)
            assertThat(document.content).isNotEmpty()
            // Verify it's a PDF by checking the magic bytes (%PDF)
            assertThat(document.content.take(4).toByteArray()).isEqualTo(byteArrayOf(0x25, 0x50, 0x44, 0x46))
        }
    }

    @Test
    fun `generate document batch successfully`() {
        // Create test data
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Report Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
        // Set up simple template
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Report Template",
        )
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        // Create batch items
        val items = (1..5).map { i ->
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", i),
                filename = "report-$i.pdf",
            )
        }

        // Generate batch
        val request = mediator.send(GenerateDocumentBatch(tenant.id, items))

        // Verify request
        assertThat(request.totalCount).isEqualTo(5)

        // Wait for completion
        await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(200, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
            }

        // Verify all items completed
        val job = mediator.query(GetGenerationJob(tenant.id, request.id))!!
        assertThat(job.request.completedCount).isEqualTo(5)
        assertThat(job.request.failedCount).isEqualTo(0)
        assertThat(job.items).hasSize(5)

        // Verify all documents were created
        val documents = mediator.query(ListDocuments(tenantId = tenant.id, templateId = template.id, limit = 10))
        assertThat(documents).hasSize(5)
    }

    /**
     * Tests that batch generation continues processing when some items fail.
     *
     * TODO: This test requires schema validation to be implemented.
     * Currently, there's no way to cause item-level failures during batch processing:
     * - Version/template validation happens upfront (before items are created)
     * - The template has no blocks, so no render failures can occur
     *
     * Once schema validation is added, update this test to use data that violates
     * the schema constraints, which will cause individual items to fail during processing.
     */
    @Test
    @org.junit.jupiter.api.Disabled("Requires schema validation to test partial failures")
    fun `batch generation continues on partial failures`() {
        // Create test data
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
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

        // Create mix of valid and invalid data
        val items = listOf(
            // Valid item
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value1"),
                filename = "doc1.pdf",
            ),
            // TODO: Add item with schema-violating data once validation is implemented
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value2"),
                filename = "doc2.pdf",
            ),
            // Valid item
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value3"),
                filename = "doc3.pdf",
            ),
        )

        // Generate batch
        val request = mediator.send(GenerateDocumentBatch(tenant.id, items))

        // Wait for completion
        await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(200, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
            }

        // Verify partial success
        val job = mediator.query(GetGenerationJob(tenant.id, request.id))!!
        assertThat(job.request.completedCount).isEqualTo(2)
        assertThat(job.request.failedCount).isEqualTo(1)

        // Verify error messages
        val failedItem = job.items.find { it.status.name == "FAILED" }
        assertThat(failedItem).isNotNull
        assertThat(failedItem!!.errorMessage).isNotNull()
    }

    @Test
    fun `cancel pending generation job`() {
        // Create test data
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
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

        // Create a large batch to ensure it stays in PENDING/IN_PROGRESS for a moment
        val items = (1..100).map { i ->
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", i),
                filename = "doc-$i.pdf",
            )
        }

        val request = mediator.send(GenerateDocumentBatch(tenant.id, items))

        // Try to cancel immediately
        val cancelled = mediator.send(CancelGenerationJob(tenant.id, request.id))

        // Should succeed if job hasn't completed yet
        if (cancelled) {
            // Wait a bit for cancellation to take effect
            await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted {
                    val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                    assertThat(job!!.request.status).isEqualTo(RequestStatus.CANCELLED)
                }
        }
        // If cancellation failed, job completed too quickly - that's OK for this test
    }

    @Test
    fun `list generation jobs filtered by status`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
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
        val requests = (1..3).map {
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = template.id,
                    variantId = variant.id,
                    versionId = version.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode().put("test", it),
                    filename = "doc-$it.pdf",
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

        // List completed jobs
        val completedJobs = mediator.query(ListGenerationJobs(tenant.id, RequestStatus.COMPLETED, 10, 0))
        assertThat(completedJobs).isNotEmpty
        assertThat(completedJobs).allMatch { it.status == RequestStatus.COMPLETED }
    }

    @Test
    fun `delete generated document`() = scenario {
        given {
            val tenant = tenant("Test Tenant")
            val template = template(tenant.id, "Test Template")
            val variant = variant(tenant.id, template.id, "Default")
            val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
            val version = version(tenant.id, template.id, variant.id, templateModel)
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            // Generate document first
            val request = execute(
                GenerateDocument(
                    tenantId = setup.tenant.id,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    versionId = setup.version.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode().put("test", "value"),
                    filename = "test.pdf",
                ),
            )

            // Wait for completion
            await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted {
                    val job = query(GetGenerationJob(setup.tenant.id, request.id))
                    assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
                }

            val job = query(GetGenerationJob(setup.tenant.id, request.id))!!
            job.items[0].documentId!!
        }.then { setup, documentId ->
            // Delete document
            val deleted = mediator.send(DeleteDocument(setup.tenant.id, documentId))
            assertThat(deleted).isTrue()

            // Verify document is gone
            val document = mediator.query(GetDocument(setup.tenant.id, documentId))
            assertThat(document).isNull()

            // Try to delete again
            val deletedAgain = mediator.send(DeleteDocument(setup.tenant.id, documentId))
            assertThat(deletedAgain).isFalse()
        }
    }

    @Test
    fun `multi-tenant isolation for generation jobs`() {
        // Create two tenants
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        val template1 = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant1.id, name = "Template 1"))
        val variant1 = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant1.id, templateId = template1.id, title = "Default", description = null, tags = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Template 1",
        )
        val version1 = mediator.send(
            UpdateDraft(
                tenantId = tenant1.id,
                templateId = template1.id,
                variantId = variant1.id,
                templateModel = templateModel,
            ),
        )!!

        // Generate document for tenant 1
        val request1 = mediator.send(
            GenerateDocument(
                tenantId = tenant1.id,
                templateId = template1.id,
                variantId = variant1.id,
                versionId = version1.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value"),
                filename = "test.pdf",
            ),
        )

        // Wait for completion
        await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val job = mediator.query(GetGenerationJob(tenant1.id, request1.id))
                assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
            }

        // Tenant 2 should not be able to access tenant 1's job
        val job2 = mediator.query(GetGenerationJob(tenant2.id, request1.id))
        assertThat(job2).isNull()

        // Tenant 2 should not see tenant 1's jobs in listing
        val jobs2 = mediator.query(ListGenerationJobs(tenant2.id, null, 10, 0))
        assertThat(jobs2).isEmpty()
    }

    // ================== Correlation ID Tests ==================

    @Test
    fun `batch with correlation IDs stores and returns them`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        val items = listOf(
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 1),
                filename = "doc1.pdf",
                correlationId = "order-123",
            ),
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 2),
                filename = "doc2.pdf",
                correlationId = "order-456",
            ),
        )

        val request = mediator.send(GenerateDocumentBatch(tenant.id, items))

        // Wait for completion
        await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
            }

        // Verify correlation IDs on items
        val job = mediator.query(GetGenerationJob(tenant.id, request.id))!!
        assertThat(job.items).hasSize(2)
        assertThat(job.items.map { it.correlationId }).containsExactlyInAnyOrder("order-123", "order-456")

        // Verify correlation IDs propagated to documents
        val documents = mediator.query(ListDocuments(tenant.id, template.id))
        assertThat(documents).hasSize(2)
        assertThat(documents.map { it.correlationId }).containsExactlyInAnyOrder("order-123", "order-456")
    }

    @Test
    fun `batch with duplicate correlationIds fails validation`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        val items = listOf(
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 1),
                filename = "doc1.pdf",
                correlationId = "duplicate-id",
            ),
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 2),
                filename = "doc2.pdf",
                correlationId = "duplicate-id",
            ),
        )

        assertThatThrownBy { GenerateDocumentBatch(tenant.id, items) }
            .isInstanceOf(BatchValidationException::class.java)
            .satisfies({ ex ->
                val batchEx = ex as BatchValidationException
                assertThat(batchEx.duplicateCorrelationIds).containsExactly("duplicate-id")
                assertThat(batchEx.duplicateFilenames).isEmpty()
            })
    }

    @Test
    fun `batch with duplicate filenames fails validation`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        val items = listOf(
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 1),
                filename = "same-name.pdf",
                correlationId = "order-1",
            ),
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 2),
                filename = "same-name.pdf",
                correlationId = "order-2",
            ),
        )

        assertThatThrownBy { GenerateDocumentBatch(tenant.id, items) }
            .isInstanceOf(BatchValidationException::class.java)
            .satisfies({ ex ->
                val batchEx = ex as BatchValidationException
                assertThat(batchEx.duplicateFilenames).containsExactly("same-name.pdf")
                assertThat(batchEx.duplicateCorrelationIds).isEmpty()
            })
    }

    @Test
    fun `batch with multiple null correlationIds is allowed`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        val items = listOf(
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 1),
                filename = "doc1.pdf",
                correlationId = null, // null is allowed
            ),
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 2),
                filename = "doc2.pdf",
                correlationId = null, // multiple nulls are allowed
            ),
        )

        // Should not throw
        val request = mediator.send(GenerateDocumentBatch(tenant.id, items))
        assertThat(request.totalCount).isEqualTo(2)
    }

    @Test
    fun `batch with multiple null filenames is allowed`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        val items = listOf(
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 1),
                filename = null, // null is allowed - will be auto-generated
                correlationId = "order-1",
            ),
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 2),
                filename = null, // multiple nulls are allowed
                correlationId = "order-2",
            ),
        )

        // Should not throw
        val request = mediator.send(GenerateDocumentBatch(tenant.id, items))
        assertThat(request.totalCount).isEqualTo(2)

        // Wait for completion and verify auto-generated filenames
        await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
            }

        val documents = mediator.query(ListDocuments(tenant.id, template.id))
        assertThat(documents).hasSize(2)
        // Auto-generated filenames should be unique (based on item UUID)
        assertThat(documents[0].filename).isNotEqualTo(documents[1].filename)
        assertThat(documents[0].filename).startsWith("document-")
        assertThat(documents[1].filename).startsWith("document-")
    }

    @Test
    fun `list documents filtered by correlationId`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        // Generate documents with different correlation IDs
        val items = listOf(
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 1),
                filename = "doc1.pdf",
                correlationId = "target-correlation",
            ),
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 2),
                filename = "doc2.pdf",
                correlationId = "other-correlation",
            ),
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 3),
                filename = "doc3.pdf",
                correlationId = null,
            ),
        )

        val request = mediator.send(GenerateDocumentBatch(tenant.id, items))

        // Wait for completion
        await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
            }

        // Filter by correlationId
        val filteredDocuments = mediator.query(
            ListDocuments(
                tenantId = tenant.id,
                correlationId = "target-correlation",
            ),
        )

        assertThat(filteredDocuments).hasSize(1)
        assertThat(filteredDocuments[0].correlationId).isEqualTo("target-correlation")
        assertThat(filteredDocuments[0].filename).isEqualTo("doc1.pdf")

        // List all documents
        val allDocuments = mediator.query(ListDocuments(tenant.id, template.id))
        assertThat(allDocuments).hasSize(3)
    }

    @Test
    fun `single document generation with correlationId`() = scenario {
        given {
            val tenant = tenant("Test Tenant")
            val template = template(tenant.id, "Test Template")
            val variant = variant(tenant.id, template.id, "Default")
            val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
            val version = version(tenant.id, template.id, variant.id, templateModel)
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            execute(
                GenerateDocument(
                    tenantId = setup.tenant.id,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    versionId = setup.version.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode().put("test", "value"),
                    filename = "single-doc.pdf",
                    correlationId = "single-order-789",
                ),
            )
        }.then { setup, request ->
            // Wait for completion
            await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted {
                    val job = mediator.query(GetGenerationJob(setup.tenant.id, request.id))
                    assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
                }

            // Verify correlationId on job item
            val job = mediator.query(GetGenerationJob(setup.tenant.id, request.id))!!
            assertThat(job.items).hasSize(1)
            assertThat(job.items[0].correlationId).isEqualTo("single-order-789")

            // Verify correlationId on document
            val documents = mediator.query(
                ListDocuments(
                    tenantId = setup.tenant.id,
                    correlationId = "single-order-789",
                ),
            )
            assertThat(documents).hasSize(1)
            assertThat(documents[0].correlationId).isEqualTo("single-order-789")
            assertThat(documents[0].filename).isEqualTo("single-doc.pdf")
        }
    }
}
