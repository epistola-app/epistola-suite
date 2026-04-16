package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

class GenerateDocumentBatchHandlerTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    private val objectMapper = ObjectMapper()

    @Test
    fun `creates batch generation request`() = withAuthentication {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = mediator.send(CreateDocumentTemplate(id = templateId, name = "Test Template"))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = mediator.send(CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Test Template",
        )
        val version = mediator.send(
            UpdateDraft(
                variantId = variantId,
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
                SELECT id, batch_id, tenant_key, template_key, variant_key, version_key, environment_key,
                       data, filename, correlation_key, document_key, status, claimed_by, claimed_at,
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
        assertThat(requests).allMatch { it.tenantKey == tenant.id }
        assertThat(requests).allMatch { it.status in setOf(RequestStatus.PENDING, RequestStatus.IN_PROGRESS, RequestStatus.COMPLETED) }
    }

    @Test
    fun `validates all items before creating request`() = withAuthentication {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = mediator.send(CreateDocumentTemplate(id = templateId, name = "Test Template"))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = mediator.send(CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Test Template",
        )
        val version = mediator.send(
            UpdateDraft(
                variantId = variantId,
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
                versionId = VersionKey.of(100), // Non-existent version for testing (valid range but doesn't exist)
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
                tenantId = TenantKey.of("dummy-tenant"),
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
                versionId = VersionKey.of(100), // Non-existent version for testing (valid range but doesn't exist)
                environmentId = TestIdHelpers.nextEnvironmentId(), // Both set
                data = objectMapper.createObjectNode(),
                filename = "test.pdf",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cannot specify both")
    }
}
