// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.CatalogNotReleasedException
import app.epistola.suite.documents.ReleaseNotFoundException
import app.epistola.suite.documents.TemplateNotInReleaseException
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.documents.VersionNotFoundException
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class GenerateDocumentHandlerTest : IntegrationTestBase() {
    private val objectMapper = ObjectMapper()

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var context: org.springframework.context.ApplicationContext

    @Test
    fun `creates generation request with valid inputs`(): Unit = withAuthentication {
        // Debug: List all command handlers
        val handlers = context.getBeansOfType(app.epistola.suite.mediator.CommandHandler::class.java)
        println("DEBUG: Found ${handlers.size} command handlers")
        handlers.forEach { (name, handler) ->
            println("DEBUG:   - $name: ${handler::class.simpleName}")
        }

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
            UpdateDraft(
                variantId = variantId,
                templateModel = templateModel,
            ),
        )!!
        val version = mediator.publishAndRelease(variantId)

        val data = objectMapper.createObjectNode().put("test", "value")

        val request = mediator.send(
            GenerateDocument(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                environmentId = null,
                data = data,
                filename = "test.pdf",
            ),
        )

        assertThat(request.id).isNotNull()
        assertThat(request.tenantKey).isEqualTo(tenant.id)
        assertThat(request.status).isIn(RequestStatus.PENDING, RequestStatus.IN_PROGRESS)
    }

    @Test
    fun `fails when the catalog has no release`(): Unit = withAuthentication {
        val tenant = createTenant("Test Tenant")

        assertThatThrownBy {
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = TestIdHelpers.nextTemplateId(),
                    variantId = TestIdHelpers.nextVariantId(),
                    data = objectMapper.createObjectNode().put("test", "value"),
                    filename = "test.pdf",
                ),
            )
        }.isInstanceOf(CatalogNotReleasedException::class.java)
            .hasMessageContaining("has no release")
    }

    @Test
    fun `fails for a template the latest release does not contain`(): Unit = withAuthentication {
        val tenant = createTenant("Test Tenant")
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
        mediator.send(CreateDocumentTemplate(id = templateId, name = "Released"))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        mediator.send(CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()))
        mediator.send(UpdateDraft(variantId = variantId, templateModel = TestTemplateBuilder.buildMinimal(name = "Released")))
        mediator.publishAndRelease(variantId)

        assertThatThrownBy {
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = TestIdHelpers.nextTemplateId(),
                    data = objectMapper.createObjectNode(),
                    filename = "test.pdf",
                ),
            )
        }.isInstanceOf(TemplateNotInReleaseException::class.java)
            .hasMessageContaining("does not contain template")
    }

    @Test
    fun `refuses a release the catalog does not have`(): Unit = withAuthentication {
        val tenant = createTenant("Test Tenant")

        assertThatThrownBy {
            mediator.send(
                GenerateDocument(
                    tenantId = tenant.id,
                    templateId = TestIdHelpers.nextTemplateId(),
                    releaseVersion = "9.9.9",
                    data = objectMapper.createObjectNode(),
                    filename = "test.pdf",
                ),
            )
        }.isInstanceOf(ReleaseNotFoundException::class.java)
            .hasMessageContaining("9.9.9")
    }

    @Test
    fun `validates releaseVersion and environmentId are mutually exclusive`() {
        assertThatThrownBy {
            GenerateDocument(
                tenantId = TenantKey.of("dummy-tenant"),
                templateId = TestIdHelpers.nextTemplateId(),
                variantId = TestIdHelpers.nextVariantId(),
                releaseVersion = "1.0.0",
                environmentId = TestIdHelpers.nextEnvironmentId(), // Both set - should fail
                data = objectMapper.createObjectNode(),
                filename = "test.pdf",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Cannot specify both")
    }
}
