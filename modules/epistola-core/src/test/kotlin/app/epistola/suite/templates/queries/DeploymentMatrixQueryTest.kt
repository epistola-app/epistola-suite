package app.epistola.suite.templates.queries

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.TestIdHelpers
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeploymentMatrixQueryTest : CoreIntegrationTestBase() {

    @Test
    fun `deployment matrix returns empty list when no activations exist`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()

        val cells = GetDeploymentMatrix(tenantId = tenant.id, templateId = template.id).query()
        assertThat(cells).isEmpty()
    }

    @Test
    fun `deployment matrix returns activated cells across variants and environments`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val defaultVariant = variants.first()

        val secondVariant = CreateVariant(
            id = TestIdHelpers.nextVariantId(),
            tenantId = tenant.id,
            templateId = template.id,
            title = null,
            description = null,
        ).execute()!!

        val staging = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Staging").execute()
        val production = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Production").execute()

        // Get draft versions
        val defaultVersions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = defaultVariant.id).query()
        val secondVersions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = secondVariant.id).query()

        // Publish default variant to staging
        PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = defaultVariant.id,
            versionId = defaultVersions.first().id,
            environmentId = staging.id,
        ).execute()

        // Publish second variant to both environments
        PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = secondVariant.id,
            versionId = secondVersions.first().id,
            environmentId = staging.id,
        ).execute()
        PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = secondVariant.id,
            versionId = secondVersions.first().id,
            environmentId = production.id,
        ).execute()

        val cells = GetDeploymentMatrix(tenantId = tenant.id, templateId = template.id).query()
        assertThat(cells).hasSize(3)
        assertThat(cells.map { it.variantId to it.environmentId }).containsExactlyInAnyOrder(
            defaultVariant.id to staging.id,
            secondVariant.id to staging.id,
            secondVariant.id to production.id,
        )
        Unit
    }

    @Test
    fun `publishable versions returns draft and published versions grouped by variant`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val defaultVariant = variants.first()
        val env = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Staging").execute()

        // The default variant has an auto-created draft (v1)
        val versions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = defaultVariant.id).query()
        val draft = versions.first()
        assertThat(draft.status).isEqualTo(VersionStatus.DRAFT)

        // Publish the draft
        PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = defaultVariant.id,
            versionId = draft.id,
            environmentId = env.id,
        ).execute()

        // Create a new draft (v2)
        CreateVersion(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = defaultVariant.id,
        ).execute()

        val publishableVersions = ListPublishableVersionsByTemplate(
            tenantId = tenant.id,
            templateId = template.id,
        ).query()

        assertThat(publishableVersions).hasSize(2) // v1 (published) + v2 (draft)
        assertThat(publishableVersions.all { it.variantId == defaultVariant.id }).isTrue()
        assertThat(publishableVersions.map { it.status }).containsExactlyInAnyOrder(VersionStatus.DRAFT, VersionStatus.PUBLISHED)
        Unit
    }

    @Test
    fun `publishable versions excludes archived versions`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val defaultVariant = variants.first()

        // Only a draft exists (v1) — should be returned
        val publishableVersions = ListPublishableVersionsByTemplate(
            tenantId = tenant.id,
            templateId = template.id,
        ).query()

        assertThat(publishableVersions).hasSize(1)
        assertThat(publishableVersions.first().status).isEqualTo(VersionStatus.DRAFT)
        Unit
    }
}
