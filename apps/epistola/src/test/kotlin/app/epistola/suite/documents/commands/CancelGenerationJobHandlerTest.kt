package app.epistola.suite.documents.commands

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

class CancelGenerationJobHandlerTest : BaseIntegrationTest() {
    private val objectMapper = ObjectMapper()

    @Test
    fun `returns false for non-existent job`() {
        val tenant = createTenant("Test Tenant")
        val randomId = GenerationRequestId.generate()

        val cancelled = mediator.send(CancelGenerationJob(tenant.id, randomId))

        assertThat(cancelled).isFalse()
    }

    @Test
    fun `returns false for job from different tenant`() {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        val template = mediator.send(CreateDocumentTemplate(id = TemplateId.generate(), tenantId = tenant1.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = VariantId.generate(), tenantId = tenant1.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
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

        // Try to cancel with different tenant
        val cancelled = mediator.send(CancelGenerationJob(tenant2.id, request.id))

        assertThat(cancelled).isFalse()
    }

    @Test
    fun `cannot cancel completed job`() {
        val tenant = createTenant("Test Tenant")
        val template = mediator.send(CreateDocumentTemplate(id = TemplateId.generate(), tenantId = tenant.id, name = "Test Template"))
        val variant = mediator.send(CreateVariant(id = VariantId.generate(), tenantId = tenant.id, templateId = template.id, title = "Default", description = null, tags = emptyMap()))!!
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

        // Wait for completion
        await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                assertThat(job!!.request.status).isIn(
                    app.epistola.suite.documents.model.RequestStatus.COMPLETED,
                    app.epistola.suite.documents.model.RequestStatus.FAILED,
                    app.epistola.suite.documents.model.RequestStatus.CANCELLED,
                )
            }

        // Try to cancel completed job
        val cancelled = mediator.send(CancelGenerationJob(tenant.id, request.id))

        assertThat(cancelled).isFalse()
    }
}
