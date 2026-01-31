package app.epistola.suite.documents.commands

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.documents.model.JobType
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class GenerateDocumentHandlerTest : BaseIntegrationTest() {
    private val objectMapper = ObjectMapper()

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var context: org.springframework.context.ApplicationContext

    @Test
    fun `creates generation request with valid inputs`() {
        // Debug: List all command handlers
        val handlers = context.getBeansOfType(app.epistola.suite.mediator.CommandHandler::class.java)
        println("DEBUG: Found ${handlers.size} command handlers")
        handlers.forEach { (name, handler) ->
            println("DEBUG:   - $name: ${handler::class.simpleName}")
        }

        val tenant = createTenant("Test Tenant")
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

        val data = objectMapper.createObjectNode().put("test", "value")

        val request = mediator.send(
            GenerateDocument(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = data,
                filename = "test.pdf",
            ),
        )

        assertThat(request.id).isNotNull()
        assertThat(request.tenantId).isEqualTo(tenant.id)
        assertThat(request.jobType).isEqualTo(JobType.SINGLE)
        assertThat(request.status).isIn(RequestStatus.PENDING, RequestStatus.IN_PROGRESS)
        assertThat(request.totalCount).isEqualTo(1)
        assertThat(request.completedCount).isEqualTo(0)
        assertThat(request.failedCount).isEqualTo(0)
    }

    @Test
    fun `fails with non-existent template`() {
        val tenant = createTenant("Test Tenant")
        val data = objectMapper.createObjectNode().put("test", "value")

        assertThatThrownBy {
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = 99999,
                    variantId = 99999,
                    versionId = 99999,
                    environmentId = null,
                    data = data,
                    filename = "test.pdf",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Template")
    }

    @Test
    fun `fails with non-existent version`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(tenant.id, "Test Template"))
        val variant = mediator.send(CreateVariant(tenant.id, template.id, "Default", null, emptyMap()))!!

        val data = objectMapper.createObjectNode().put("test", "value")

        assertThatThrownBy {
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = template.id,
                    variantId = variant.id,
                    versionId = 99999,
                    environmentId = null,
                    data = data,
                    filename = "test.pdf",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Version")
    }

    @Test
    fun `validates versionId and environmentId are mutually exclusive`() {
        assertThatThrownBy {
            GenerateDocument(
                tenantId = 1,
                templateId = 1,
                variantId = 1,
                versionId = 1,
                environmentId = 1, // Both set - should fail
                data = objectMapper.createObjectNode(),
                filename = "test.pdf",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Exactly one")
    }

    @Test
    fun `validates either versionId or environmentId is set`() {
        assertThatThrownBy {
            GenerateDocument(
                tenantId = 1,
                templateId = 1,
                variantId = 1,
                versionId = null,
                environmentId = null, // Neither set - should fail
                data = objectMapper.createObjectNode(),
                filename = "test.pdf",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Exactly one")
    }
}
