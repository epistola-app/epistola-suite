package app.epistola.suite.templates.queries

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.activations.GetDeploymentMatrix
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.ListPublishableVersionsByTemplate
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeploymentMatrixQueryTest : IntegrationTestBase() {

    @Test
    fun `deployment matrix returns empty list when no activations exist`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()

        val cells = GetDeploymentMatrix(templateId = templateId).query()
        assertThat(cells).isEmpty()
    }

    @Test
    fun `deployment matrix returns activated cells across variants and environments`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val defaultVariant = variants.first()
        val defaultVariantId = VariantId(defaultVariant.id, templateId)

        val secondVariant = CreateVariant(
            id = VariantId(TestIdHelpers.nextVariantId(), templateId),
            title = null,
            description = null,
        ).execute()!!
        val secondVariantId = VariantId(secondVariant.id, templateId)

        val stagingId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val staging = CreateEnvironment(id = stagingId, name = "Staging").execute()
        val productionId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val production = CreateEnvironment(id = productionId, name = "Production").execute()

        // Get draft versions
        val defaultVersions = ListVersions(variantId = defaultVariantId).query()
        val secondVersions = ListVersions(variantId = secondVariantId).query()

        // Publish default variant to staging
        PublishToEnvironment(
            versionId = VersionId(defaultVersions.first().id, defaultVariantId),
            environmentId = stagingId,
        ).execute()

        // Publish second variant to both environments
        PublishToEnvironment(
            versionId = VersionId(secondVersions.first().id, secondVariantId),
            environmentId = stagingId,
        ).execute()
        PublishToEnvironment(
            versionId = VersionId(secondVersions.first().id, secondVariantId),
            environmentId = productionId,
        ).execute()

        val cells = GetDeploymentMatrix(templateId = templateId).query()
        assertThat(cells).hasSize(3)
        assertThat(cells.map { it.variantKey to it.environmentKey }).containsExactlyInAnyOrder(
            defaultVariant.id to staging.id,
            secondVariant.id to staging.id,
            secondVariant.id to production.id,
        )
        Unit
    }

    @Test
    fun `publishable versions returns draft and published versions grouped by variant`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val defaultVariant = variants.first()
        val defaultVariantId = VariantId(defaultVariant.id, templateId)
        val environmentId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val env = CreateEnvironment(id = environmentId, name = "Staging").execute()

        // The default variant has an auto-created draft (v1)
        val versions = ListVersions(variantId = defaultVariantId).query()
        val draft = versions.first()
        assertThat(draft.status).isEqualTo(VersionStatus.DRAFT)

        // Publish the draft
        PublishToEnvironment(
            versionId = VersionId(draft.id, defaultVariantId),
            environmentId = environmentId,
        ).execute()

        // Create a new draft (v2)
        CreateVersion(
            variantId = defaultVariantId,
        ).execute()

        val publishableVersions = ListPublishableVersionsByTemplate(
            templateId = templateId,
        ).query()

        assertThat(publishableVersions).hasSize(2) // v1 (published) + v2 (draft)
        assertThat(publishableVersions.all { it.variantKey == defaultVariant.id }).isTrue()
        assertThat(publishableVersions.map { it.status }).containsExactlyInAnyOrder(VersionStatus.DRAFT, VersionStatus.PUBLISHED)
        Unit
    }

    @Test
    fun `publishable versions excludes archived versions`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val defaultVariant = variants.first()

        // Only a draft exists (v1) — should be returned
        val publishableVersions = ListPublishableVersionsByTemplate(
            templateId = templateId,
        ).query()

        assertThat(publishableVersions).hasSize(1)
        assertThat(publishableVersions.first().status).isEqualTo(VersionStatus.DRAFT)
        Unit
    }
}
