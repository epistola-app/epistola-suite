package app.epistola.suite.templates.commands

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class UpdateDocumentTemplateHandlerTest : IntegrationTestBase() {
    private val objectMapper = ObjectMapper()

    @Test
    fun `returns existing template when no update fields are provided`() = withMediator {
        val templateId = createTemplateId()
        val created = CreateDocumentTemplate(id = templateId, name = "Original Name").execute()

        val result = UpdateDocumentTemplate(id = templateId).execute()

        assertThat(result).isNotNull()
        assertThat(result!!.template.id).isEqualTo(created.id)
        assertThat(result.template.name).isEqualTo("Original Name")
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `throws when schema and provided examples are incompatible without forceUpdate`() = withMediator {
        val templateId = createTemplateId()
        CreateDocumentTemplate(id = templateId, name = "Template").execute()

        val badExamples = listOf(
            DataExample(
                id = "ex-1",
                name = "Bad Example",
                data = objectMapper.createObjectNode().put("count", "not-a-number"),
            ),
        )

        assertValidationErrorContainsExample("Bad Example") {
            UpdateDocumentTemplate(
                id = templateId,
                dataModel = schemaWithInteger("count"),
                dataExamples = badExamples,
            ).execute()
        }
    }

    @Test
    fun `returns warnings and still updates when forceUpdate is true`() = withMediator {
        val templateId = createTemplateId()
        CreateDocumentTemplate(id = templateId, name = "Template").execute()

        val badExamples = listOf(
            DataExample(
                id = "ex-1",
                name = "Bad Example",
                data = objectMapper.createObjectNode().put("count", "not-a-number"),
            ),
        )

        val result = UpdateDocumentTemplate(
            id = templateId,
            name = "Renamed With Warnings",
            dataModel = schemaWithInteger("count"),
            dataExamples = badExamples,
            forceUpdate = true,
        ).execute()

        assertThat(result).isNotNull()
        assertThat(result!!.template.name).isEqualTo("Renamed With Warnings")
        assertThat(result.warnings).containsKey("Bad Example")
        assertThat(result.template.draftDataModel).isNotNull()
        assertThat(result.template.draftDataExamples).isNotNull()
    }

    @Test
    fun `saving contract updates draft columns without changing active contract`() = withMediator {
        val templateId = createTemplateId()
        CreateDocumentTemplate(id = templateId, name = "Template").execute()

        UpdateDocumentTemplate(
            id = templateId,
            dataModel = schemaWithInteger("count"),
            dataExamples = listOf(
                DataExample(
                    id = "ex-1",
                    name = "Draft Example",
                    data = objectMapper.createObjectNode().put("count", 1),
                ),
            ),
        ).execute()

        val persisted = GetDocumentTemplate(templateId).query()

        assertThat(persisted).isNotNull()
        assertThat(persisted!!.publishedDataModel).isNull()
        assertThat(persisted.publishedDataExamples).isEmpty()
        assertThat(persisted.draftDataModel).isNotNull()
        assertThat(persisted.draftDataExamples).hasSize(1)
        assertThat(persisted.dataModel).isNotNull()
        assertThat(persisted.dataExamples).hasSize(1)
    }

    @Test
    fun `clearThemeId takes precedence over themeId`() = withMediator {
        val tenant = createTenant("Theme Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
        CreateDocumentTemplate(id = templateId, name = "Template").execute()

        val themeId = ThemeId(ThemeKey.of("theme-a"), tenantId)
        CreateTheme(id = themeId, name = "Theme A").execute()

        UpdateDocumentTemplate(id = templateId, themeId = themeId.key).execute()

        val result = UpdateDocumentTemplate(
            id = templateId,
            clearThemeId = true,
            themeId = ThemeKey.of("non-existent-theme"),
        ).execute()

        assertThat(result).isNotNull()
        assertThat(result!!.template.themeKey).isNull()
    }

    @Test
    fun `examples-only update validates against existing schema`() = withMediator {
        val templateId = createTemplateId()
        CreateDocumentTemplate(id = templateId, name = "Template").execute()

        UpdateDocumentTemplate(
            id = templateId,
            dataModel = schemaWithInteger("count"),
        ).execute()

        val badExamples = listOf(
            DataExample(
                id = "ex-1",
                name = "Bad Example",
                data = objectMapper.createObjectNode().put("count", "wrong"),
            ),
        )

        assertValidationErrorContainsExample("Bad Example") {
            UpdateDocumentTemplate(
                id = templateId,
                dataExamples = badExamples,
            ).execute()
        }
    }

    @Test
    fun `schema-only update validates existing examples`() = withMediator {
        val templateId = createTemplateId()
        CreateDocumentTemplate(id = templateId, name = "Template").execute()

        val existingExamples = listOf(
            DataExample(
                id = "ex-1",
                name = "Existing Example",
                data = objectMapper.createObjectNode().put("value", "hello"),
            ),
        )

        UpdateDocumentTemplate(
            id = templateId,
            dataExamples = existingExamples,
        ).execute()

        assertValidationErrorContainsExample("Existing Example") {
            UpdateDocumentTemplate(
                id = templateId,
                dataModel = schemaWithInteger("value"),
            ).execute()
        }
    }

    @Test
    fun `returns null when template does not exist`() = withMediator {
        val tenant = createTenant("Missing Template")
        val missingId = TemplateId(TestIdHelpers.nextTemplateId(), TenantId(tenant.id))

        val result = UpdateDocumentTemplate(
            id = missingId,
            dataModel = schemaWithInteger("count"),
        ).execute()

        assertThat(result).isNull()
        assertThat(GetDocumentTemplate(missingId).query()).isNull()
    }

    private fun createTemplateId(): TemplateId {
        val tenant = createTenant("Update Template")
        return TemplateId(TestIdHelpers.nextTemplateId(), TenantId(tenant.id))
    }

    private fun schemaWithInteger(field: String): ObjectNode = objectMapper.createObjectNode()
        .put("type", "object")
        .set(
            "properties",
            objectMapper.createObjectNode().set(field, objectMapper.createObjectNode().put("type", "integer")),
        )

    private fun assertValidationErrorContainsExample(
        exampleName: String,
        block: () -> Unit,
    ) {
        val exception = runCatching { block() }.exceptionOrNull()
        assertThat(exception).isInstanceOf(DataModelValidationException::class.java)
        val validation = exception as DataModelValidationException
        assertThat(validation.validationErrors).containsKey(exampleName)
    }
}
