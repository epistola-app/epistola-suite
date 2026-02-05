package app.epistola.suite.documents.commands

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

class GenerateDocumentBatchHandlerTest : CoreIntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    private val objectMapper = ObjectMapper()

    @Test
    fun `creates batch generation request`() {
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

        val items = (1..3).map { i ->
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", i),
                filename = "doc-$i.pdf",
            )
        }

        val batchId = mediator.send(GenerateDocumentBatch(tenant.id, items))

        assertThat(batchId.value).isNotNull()

        // Verify requests were created for the batch
        val requests = jdbi.withHandle<List<app.epistola.suite.documents.model.DocumentGenerationRequest>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, batch_id, tenant_id, template_id, variant_id, version_id, environment_id,
                       data, filename, correlation_id, document_id, status, claimed_by, claimed_at,
                       error_message, created_at, started_at, completed_at, expires_at
                FROM document_generation_requests
                WHERE batch_id = :batchId
                """,
            )
                .bind("batchId", batchId)
                .mapTo(app.epistola.suite.documents.model.DocumentGenerationRequest::class.java)
                .list()
        }

        assertThat(requests).hasSize(3)
        assertThat(requests).allMatch { it.tenantId == tenant.id }
        assertThat(requests).allMatch { it.status in setOf(RequestStatus.PENDING, RequestStatus.IN_PROGRESS) }
    }

    @Test
    fun `validates all items before creating request`() {
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

        val items = listOf(
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 1),
                filename = "doc-1.pdf",
            ),
            BatchGenerationItem(
                templateId = TestIdHelpers.nextTemplateId(), // Non-existent template
                variantId = TestIdHelpers.nextVariantId(),
                versionId = VersionId.of(100), // Non-existent version for testing (valid range but doesn't exist)
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 2),
                filename = "doc-2.pdf",
            ),
        )

        assertThatThrownBy {
            mediator.send(GenerateDocumentBatch(tenant.id, items))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Item 1")
            .hasMessageContaining("Template")
    }

    @Test
    fun `requires at least one item`() {
        assertThatThrownBy {
            GenerateDocumentBatch(
                tenantId = TenantId.of("dummy-tenant"),
                items = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("At least one item")
    }

    @Test
    fun `validates item versionId and environmentId are mutually exclusive`() {
        assertThatThrownBy {
            BatchGenerationItem(
                templateId = TestIdHelpers.nextTemplateId(),
                variantId = TestIdHelpers.nextVariantId(),
                versionId = VersionId.of(100), // Non-existent version for testing (valid range but doesn't exist)
                environmentId = TestIdHelpers.nextEnvironmentId(), // Both set
                data = objectMapper.createObjectNode(),
                filename = "test.pdf",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Exactly one")
    }
}
