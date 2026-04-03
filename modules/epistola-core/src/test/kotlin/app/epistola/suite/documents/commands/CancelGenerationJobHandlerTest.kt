package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

class CancelGenerationJobHandlerTest : IntegrationTestBase() {
    private val objectMapper = ObjectMapper()

    @Test
    fun `returns false for non-existent job`() = withAuthentication {
        val tenant = createTenant("Test Tenant")
        val randomId = GenerationRequestKey.generate()

        val cancelled = mediator.send(CancelGenerationJob(tenant.id, randomId))

        assertThat(cancelled).isFalse()
    }

    @Test
    fun `returns false for job from different tenant`() = withAuthentication {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        val tenantId1 = TenantId(tenant1.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId1)
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
    fun `cannot cancel completed job`() = withAuthentication {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
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
                SecurityContext.runWithPrincipal(testUser) {
                    val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                    assertThat(job!!.request.status).isIn(
                        app.epistola.suite.documents.model.RequestStatus.COMPLETED,
                        app.epistola.suite.documents.model.RequestStatus.FAILED,
                        app.epistola.suite.documents.model.RequestStatus.CANCELLED,
                    )
                }
            }

        // Try to cancel completed job
        val cancelled = mediator.send(CancelGenerationJob(tenant.id, request.id))

        assertThat(cancelled).isFalse()
    }
}
