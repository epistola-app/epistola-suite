package app.epistola.suite.documents

import app.epistola.suite.CoreTestcontainersConfiguration
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetDocument
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.tenants.commands.CreateTenant
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

/**
 * Integration test for async JobPoller mechanism with REAL PDF generation.
 *
 * This test does NOT use FakeExecutorTestConfiguration, so it uses the real
 * DocumentGenerationExecutor. It validates that JobPoller picks up pending
 * requests and processes them correctly with real PDF rendering.
 */
@SpringBootTest(
    properties = [
        "epistola.demo.enabled=false",
    ],
)
@Import(CoreTestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "epistola.generation.polling.enabled=true",
        "epistola.generation.polling.interval-ms=500", // Fast polling for tests
        "epistola.generation.polling.max-concurrent-jobs=5",
    ],
)
@Timeout(60) // Allow more time for real async processing
class JobPollerIntegrationTest {
    @Autowired
    private lateinit var mediator: Mediator

    private val objectMapper = ObjectMapper()

    @Test
    fun `JobPoller processes pending requests asynchronously with real PDF generation`() {
        // Create test data
        val tenant = mediator.send(
            CreateTenant(
                id = TenantId.of("test-tenant-${System.currentTimeMillis()}"),
                name = "Test Tenant",
            ),
        )
        val template = mediator.send(
            CreateDocumentTemplate(
                id = TestIdHelpers.nextTemplateId(),
                tenantId = tenant.id,
                name = "Test Template",
            ),
        )
        val variant = mediator.send(
            CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "Default",
                description = null,
                tags = emptyMap(),
            ),
        )!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = "Test Template")
        val version = mediator.send(
            UpdateDraft(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                templateModel = templateModel,
            ),
        )!!

        // Generate document (will stay PENDING until JobPoller picks it up)
        val request = mediator.send(
            GenerateDocument(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value"),
                filename = "async-test.pdf",
            ),
        )

        // Verify request starts as PENDING
        assertThat(request.status).isEqualTo(RequestStatus.PENDING)

        // Wait for JobPoller to process it (with real PDF generation)
        await()
            .atMost(30, TimeUnit.SECONDS) // Longer timeout for real generation
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                assertThat(job).isNotNull
                assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
            }

        // Verify real document was created with valid PDF
        val job = mediator.query(GetGenerationJob(tenant.id, request.id))!!
        assertThat(job.request.documentId).isNotNull()

        // Verify it's a REAL PDF (not fake) by checking size
        val document = mediator.query(GetDocument(tenant.id, job.request.documentId!!))!!
        assertThat(document.sizeBytes).isGreaterThan(100) // Real PDFs are bigger than fake (~40 bytes)
        assertThat(document.content.take(4).toByteArray())
            .isEqualTo(byteArrayOf(0x25, 0x50, 0x44, 0x46)) // %PDF magic bytes
    }
}
