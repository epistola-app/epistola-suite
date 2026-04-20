package app.epistola.suite.templates.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class PublishDocumentTemplateContractDraftHandlerTest : IntegrationTestBase() {
    private val objectMapper = ObjectMapper()

    @Test
    fun `publish promotes draft contract into active columns`() = withMediator {
        val templateId = createTemplateId()
        CreateDocumentTemplate(id = templateId, name = "Template").execute()

        UpdateDocumentTemplate(
            id = templateId,
            dataModel = schemaWithRequiredString("name"),
            dataExamples = listOf(
                DataExample(
                    id = "ex-1",
                    name = "Draft Example",
                    data = objectMapper.createObjectNode().put("name", "Ada"),
                ),
            ),
        ).execute()

        val result = PublishDocumentTemplateContractDraft(id = templateId).execute()

        assertThat(result).isNotNull()
        assertThat(result!!.template.publishedDataModel).isNotNull()
        assertThat(result.template.publishedDataExamples).hasSize(1)
        assertThat(result.template.draftDataModel).isNull()
        assertThat(result.template.draftDataExamples).isNull()

        val persisted = GetDocumentTemplate(templateId).query()
        assertThat(persisted).isNotNull()
        assertThat(persisted!!.publishedDataModel).isNotNull()
        assertThat(persisted.publishedDataExamples).hasSize(1)
        assertThat(persisted.draftDataModel).isNull()
        assertThat(persisted.draftDataExamples).isNull()
    }

    @Test
    fun `publish blocks when recent usage is incompatible with draft schema`() = withMediator {
        val templateId = createTemplateId()
        CreateDocumentTemplate(id = templateId, name = "Template").execute()

        GenerateDocument(
            tenantId = templateId.tenantKey,
            templateId = templateId.key,
            variantId = defaultVariantId(templateId).key,
            versionId = VersionKey.of(1),
            environmentId = null,
            data = objectMapper.createObjectNode(),
            filename = "sample.pdf",
        ).execute()

        UpdateDocumentTemplate(
            id = templateId,
            dataModel = schemaWithRequiredString("name"),
            dataExamples = listOf(
                DataExample(
                    id = "ex-1",
                    name = "Draft Example",
                    data = objectMapper.createObjectNode().put("name", "Ada"),
                ),
            ),
        ).execute()

        val exception = runCatching {
            PublishDocumentTemplateContractDraft(id = templateId).execute()
        }.exceptionOrNull()

        assertThat(exception).isInstanceOf(PublishDocumentTemplateContractValidationException::class.java)
        val validation = exception as PublishDocumentTemplateContractValidationException
        assertThat(validation.validationErrors.keys).anyMatch { it.startsWith("recent-request:") }
        assertThat(validation.recentUsage).isNotNull()

        val persisted = GetDocumentTemplate(templateId).query()
        assertThat(persisted).isNotNull()
        assertThat(persisted!!.publishedDataModel).isNull()
        assertThat(persisted.draftDataModel).isNotNull()
    }

    @Test
    fun `publish force override allows recent usage incompatibility`() = withMediator {
        val templateId = createTemplateId()
        CreateDocumentTemplate(id = templateId, name = "Template").execute()

        GenerateDocument(
            tenantId = templateId.tenantKey,
            templateId = templateId.key,
            variantId = defaultVariantId(templateId).key,
            versionId = VersionKey.of(1),
            environmentId = null,
            data = objectMapper.createObjectNode(),
            filename = "sample.pdf",
        ).execute()

        UpdateDocumentTemplate(
            id = templateId,
            dataModel = schemaWithRequiredString("name"),
            dataExamples = listOf(
                DataExample(
                    id = "ex-1",
                    name = "Draft Example",
                    data = objectMapper.createObjectNode().put("name", "Ada"),
                ),
            ),
        ).execute()

        val result = PublishDocumentTemplateContractDraft(id = templateId, forceUpdate = true).execute()

        assertThat(result).isNotNull()
        assertThat(result!!.template.publishedDataModel).isNotNull()
        assertThat(result.template.draftDataModel).isNull()

        val persisted = GetDocumentTemplate(templateId).query()
        assertThat(persisted).isNotNull()
        assertThat(persisted!!.publishedDataModel).isNotNull()
        assertThat(persisted.draftDataModel).isNull()
    }

    private fun createTemplateId(): TemplateId {
        val tenant = createTenant("Publish Contract")
        val tenantId = TenantId(tenant.id)
        return TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
    }

    private fun defaultVariantId(templateId: TemplateId): VariantId = VariantId(
        VariantKey.of("${templateId.key}-default"),
        templateId,
    )

    private fun schemaWithRequiredString(field: String): ObjectNode = objectMapper.createObjectNode()
        .put("type", "object")
        .set(
            "properties",
            objectMapper.createObjectNode().set(field, objectMapper.createObjectNode().put("type", "string")),
        )
        .set(
            "required",
            objectMapper.createArrayNode().add(field),
        )
}
