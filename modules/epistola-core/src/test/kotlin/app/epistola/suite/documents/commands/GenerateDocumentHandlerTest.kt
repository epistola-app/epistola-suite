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
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class GenerateDocumentHandlerTest : CoreIntegrationTestBase() {
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
        assertThat(request.status).isIn(RequestStatus.PENDING, RequestStatus.IN_PROGRESS)
    }

    @Test
    fun `fails with non-existent template`() {
        val tenant = createTenant("Test Tenant")
        val data = objectMapper.createObjectNode().put("test", "value")

        assertThatThrownBy {
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = TestIdHelpers.nextTemplateId(),
                    variantId = TestIdHelpers.nextVariantId(),
                    versionId = VersionId.of(100), // Non-existent version for testing (valid range but doesn't exist)
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
        val template = mediator.send(CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = TestIdHelpers.nextVariantId(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, attributes = emptyMap()))!!

        val data = objectMapper.createObjectNode().put("test", "value")

        assertThatThrownBy {
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = template.id,
                    variantId = variant.id,
                    versionId = VersionId.of(100), // Non-existent version for testing (valid range but doesn't exist)
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
                tenantId = TenantId.of("dummy-tenant"),
                templateId = TestIdHelpers.nextTemplateId(),
                variantId = TestIdHelpers.nextVariantId(),
                versionId = VersionId.of(1),
                environmentId = TestIdHelpers.nextEnvironmentId(), // Both set - should fail
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
                tenantId = TenantId.of("dummy-tenant"),
                templateId = TestIdHelpers.nextTemplateId(),
                variantId = TestIdHelpers.nextVariantId(),
                versionId = null,
                environmentId = null, // Neither set - should fail
                data = objectMapper.createObjectNode(),
                filename = "test.pdf",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Exactly one")
    }
}
