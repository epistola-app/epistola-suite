package app.epistola.suite.documents.commands

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.documents.model.JobType
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.tenants.commands.CreateTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class GenerateDocumentBatchHandlerTest : BaseIntegrationTest() {
    private val objectMapper = ObjectMapper()

    @Test
    fun `creates batch generation request`() {
        val tenant = mediator.send(CreateTenant("Test Tenant"))
        val template = mediator.send(CreateDocumentTemplate(tenant.id, "Test Template"))
        val variant = mediator.send(CreateVariant(tenant.id, template.id, "Default", null, emptyMap()))!!
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

        val request = mediator.send(GenerateDocumentBatch(tenant.id, items))

        assertThat(request.id).isNotNull()
        assertThat(request.tenantId).isEqualTo(tenant.id)
        assertThat(request.jobType).isEqualTo(JobType.BATCH)
        assertThat(request.status).isIn(RequestStatus.PENDING, RequestStatus.IN_PROGRESS)
        assertThat(request.totalCount).isEqualTo(3)
        assertThat(request.completedCount).isEqualTo(0)
        assertThat(request.failedCount).isEqualTo(0)
    }

    @Test
    fun `validates all items before creating request`() {
        val tenant = mediator.send(CreateTenant("Test Tenant"))
        val template = mediator.send(CreateDocumentTemplate(tenant.id, "Test Template"))
        val variant = mediator.send(CreateVariant(tenant.id, template.id, "Default", null, emptyMap()))!!
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
                templateId = 99999, // Non-existent template
                variantId = 99999,
                versionId = 99999,
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
                tenantId = 1,
                items = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("At least one item")
    }

    @Test
    fun `validates item versionId and environmentId are mutually exclusive`() {
        assertThatThrownBy {
            BatchGenerationItem(
                templateId = 1,
                variantId = 1,
                versionId = 1,
                environmentId = 1, // Both set
                data = objectMapper.createObjectNode(),
                filename = "test.pdf",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Exactly one")
    }
}
