package app.epistola.suite.templates.queries

import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.ImportTemplateInput
import app.epistola.suite.templates.commands.ImportTemplates
import app.epistola.suite.templates.commands.ImportVariantInput
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.ListVersions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExportTemplatesTest : IntegrationTestBase() {

    @Test
    fun `export returns empty list for tenant with no templates`(): Unit = withMediator {
        val tenant = createTenant("Empty Tenant")
        val tenantId = TenantId(tenant.id)

        val result = ExportTemplates(tenantId).query()

        assertThat(result.templates).isEmpty()
    }

    @Test
    fun `export single template with single default variant`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
        CreateDocumentTemplate(id = templateId, name = "Invoice").execute()

        val result = ExportTemplates(tenantId).query()

        assertThat(result.templates).hasSize(1)
        val exported = result.templates.first()
        assertThat(exported.slug).isEqualTo(templateId.key.value)
        assertThat(exported.name).isEqualTo("Invoice")
        assertThat(exported.variants).hasSize(1)
        assertThat(exported.variants.first().isDefault).isTrue()
        assertThat(exported.version).isEqualTo("draft")
    }

    @Test
    fun `export template with multiple variants preserves isDefault flags`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
        CreateDocumentTemplate(id = templateId, name = "Invoice").execute()

        val secondVariant = CreateVariant(
            id = VariantId(TestIdHelpers.nextVariantId(), templateId),
            title = "English",
            description = null,
        ).execute()!!

        val result = ExportTemplates(tenantId).query()

        assertThat(result.templates).hasSize(1)
        val exported = result.templates.first()
        assertThat(exported.variants).hasSize(2)
        assertThat(exported.variants.count { it.isDefault }).isEqualTo(1)
        assertThat(exported.variants.find { it.id == secondVariant.id.value }?.title).isEqualTo("English")
    }

    @Test
    fun `export prefers published version over draft for templateModel`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
        CreateDocumentTemplate(id = templateId, name = "Invoice").execute()

        val variants = ListVariants(templateId = templateId).query()
        val defaultVariantId = VariantId(variants.first().id, templateId)
        val versions = ListVersions(variantId = defaultVariantId).query()

        // Create an environment and publish the draft
        val envId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        CreateEnvironment(id = envId, name = "Staging").execute()
        PublishToEnvironment(
            versionId = VersionId(versions.first().id, defaultVariantId),
            environmentId = envId,
        ).execute()

        val result = ExportTemplates(tenantId).query()

        assertThat(result.templates).hasSize(1)
        val exported = result.templates.first()
        // Version should be the published version number, not "draft"
        assertThat(exported.version).isNotEqualTo("draft")
    }

    @Test
    fun `export includes environment activations in publishTo`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
        CreateDocumentTemplate(id = templateId, name = "Invoice").execute()

        val variants = ListVariants(templateId = templateId).query()
        val defaultVariantId = VariantId(variants.first().id, templateId)
        val versions = ListVersions(variantId = defaultVariantId).query()

        val stagingId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        CreateEnvironment(id = stagingId, name = "Staging").execute()
        val prodId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        CreateEnvironment(id = prodId, name = "Production").execute()

        PublishToEnvironment(
            versionId = VersionId(versions.first().id, defaultVariantId),
            environmentId = stagingId,
        ).execute()
        PublishToEnvironment(
            versionId = VersionId(versions.first().id, defaultVariantId),
            environmentId = prodId,
        ).execute()

        val result = ExportTemplates(tenantId).query()

        assertThat(result.templates.first().publishTo).containsExactlyInAnyOrder(
            stagingId.key.value,
            prodId.key.value,
        )
    }

    @Test
    fun `round-trip import then export produces compatible output`(): Unit = withMediator {
        val tenant = createTenant("Round-trip Tenant")
        val tenantId = TenantId(tenant.id)

        // Create environments first
        val stagingId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        CreateEnvironment(id = stagingId, name = "Staging").execute()

        // Build import input
        val templateModel = app.epistola.template.model.TemplateDocument(
            root = "root",
            nodes = emptyMap(),
            slots = emptyMap(),
        )

        val importInput = listOf(
            ImportTemplateInput(
                slug = "round-trip-template",
                name = "Round-trip Template",
                version = "1",
                dataModel = null,
                dataExamples = emptyList(),
                templateModel = templateModel,
                variants = listOf(
                    ImportVariantInput(
                        id = "default",
                        title = null,
                        attributes = mapOf("lang" to "en"),
                        templateModel = null,
                        isDefault = true,
                    ),
                ),
                publishTo = listOf(stagingId.key.value),
            ),
        )

        // Import
        ImportTemplates(tenantId = tenantId, templates = importInput).execute()

        // Export
        val exportResult = ExportTemplates(tenantId).query()

        assertThat(exportResult.templates).hasSize(1)
        val exported = exportResult.templates.first()
        assertThat(exported.slug).isEqualTo("round-trip-template")
        assertThat(exported.name).isEqualTo("Round-trip Template")
        assertThat(exported.variants).hasSize(1)
        assertThat(exported.variants.first().id).isEqualTo("default")
        assertThat(exported.variants.first().isDefault).isTrue()
        assertThat(exported.variants.first().attributes).isEqualTo(mapOf("lang" to "en"))
        assertThat(exported.templateModel.root).isEqualTo("root")
        assertThat(exported.publishTo).containsExactly(stagingId.key.value)
        // The variant templateModel should be null (same as top-level)
        assertThat(exported.variants.first().templateModel).isNull()
    }
}
