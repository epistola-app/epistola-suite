// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.GenerationJobNotCancellableException
import app.epistola.suite.documents.GenerationJobNotFoundException
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class CancelGenerationJobHandlerTest : IntegrationTestBase() {
    private val objectMapper = ObjectMapper()

    @Test
    fun `throws not found for non-existent job`(): Unit = withAuthentication {
        val tenant = createTenant("Test Tenant")
        val randomId = GenerationRequestKey.generate()

        assertThatThrownBy { mediator.send(CancelGenerationJob(tenant.id, randomId)) }
            .isInstanceOf(GenerationJobNotFoundException::class.java)
    }

    @Test
    fun `throws not found for job from different tenant`(): Unit = withAuthentication {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        val tenantId1 = TenantId(tenant1.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId1))
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
        assertThatThrownBy { mediator.send(CancelGenerationJob(tenant2.id, request.id)) }
            .isInstanceOf(GenerationJobNotFoundException::class.java)
    }

    @Test
    fun `cannot cancel completed job`(): Unit = withAuthentication {
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

        // Drain the tenant's pending generation jobs synchronously
        drainGenerationJobs(tenant.id)

        // Try to cancel a job that already reached a terminal state
        assertThatThrownBy { mediator.send(CancelGenerationJob(tenant.id, request.id)) }
            .isInstanceOf(GenerationJobNotCancellableException::class.java)
    }
}
