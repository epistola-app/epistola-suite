// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
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
    fun `creates batch generation request`(): Unit = withAuthentication {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = mediator.send(CreateDocumentTemplate(id = templateId, name = "Test Template"))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = mediator.send(CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Test Template",
        )
        mediator.send(
            UpdateVariantContent(
                variantId = variantId,
                templateModel = templateModel,
            ),
        )
        val version = mediator.publishAndRelease(variantId)

        val items = (1..3).map { i ->
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", i),
                filename = "doc-$i.pdf",
                catalogKey = CatalogKey.DEFAULT,
            )
        }

        val batchId = mediator.send(GenerateDocumentBatch(tenant.id, items))

        assertThat(batchId.value).isNotNull()

        // Verify requests were created for the batch
        val requests = jdbi.withHandle<List<app.epistola.suite.documents.model.DocumentGenerationRequest>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, batch_id, tenant_key, catalog_key, template_key, variant_key, version_key, environment_key, release_version,
                       data, filename, correlation_id, document_key, status, claimed_by, claimed_at,
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
    fun `validates all items before creating request`(): Unit = withAuthentication {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = mediator.send(CreateDocumentTemplate(id = templateId, name = "Test Template"))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = mediator.send(CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(
            name = "Test Template",
        )
        mediator.send(
            UpdateVariantContent(
                variantId = variantId,
                templateModel = templateModel,
            ),
        )
        val version = mediator.publishAndRelease(variantId)

        val items = listOf(
            BatchGenerationItem(
                templateId = template.id,
                variantId = variant.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 1),
                filename = "doc-1.pdf",
                catalogKey = CatalogKey.DEFAULT,
            ),
            BatchGenerationItem(
                templateId = TestIdHelpers.nextTemplateId(), // Non-existent template
                variantId = TestIdHelpers.nextVariantId(),
                environmentId = null,
                data = objectMapper.createObjectNode().put("id", 2),
                filename = "doc-2.pdf",
                catalogKey = CatalogKey.DEFAULT,
            ),
        )

        assertThatThrownBy {
            mediator.send(GenerateDocumentBatch(tenant.id, items))
        }.isInstanceOf(app.epistola.suite.documents.TemplateNotInReleaseException::class.java)
            .hasMessageContaining("does not contain template")
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
    fun `an item version selector combines with an environment`() {
        // Contract 2.0: the selector picks among the releases the environment serves.
        assertThatCode {
            BatchGenerationItem(
                templateId = TestIdHelpers.nextTemplateId(),
                variantId = TestIdHelpers.nextVariantId(),
                releaseVersion = "1.0.0",
                environmentId = TestIdHelpers.nextEnvironmentId(),
                data = objectMapper.createObjectNode(),
                filename = "test.pdf",
                catalogKey = CatalogKey.DEFAULT,
            )
        }.doesNotThrowAnyException()
    }
}
