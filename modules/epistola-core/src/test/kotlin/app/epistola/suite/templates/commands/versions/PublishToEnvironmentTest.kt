package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.activations.RemoveActivation
import app.epistola.suite.templates.commands.contracts.PublishContractVersion
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.activations.ListActivations
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PublishToEnvironmentTest : IntegrationTestBase() {

    @Test
    fun `publish draft to environment marks it published and creates activation`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val variant = variants.first()
        val variantId = VariantId(variant.id, templateId)
        val envKey = TestIdHelpers.nextEnvironmentId()
        val environmentId = EnvironmentId(envKey, tenantId)
        val env = CreateEnvironment(id = environmentId, name = "Staging").execute()

        // Get the draft version (auto-created with variant)
        val versions = ListVersions(variantId = variantId).query()
        val draft = versions.first()
        assertThat(draft.status).isEqualTo(VersionStatus.DRAFT)

        val versionId = VersionId(draft.id, variantId)

        // Publish the contract first (guard rejects draft contracts)
        PublishContractVersion(templateId = templateId).execute()

        val result = PublishToEnvironment(
            versionId = versionId,
            environmentId = environmentId,
        ).execute()

        assertThat(result).isNotNull
        assertThat(result!!.version.status).isEqualTo(VersionStatus.PUBLISHED)
        assertThat(result.version.publishedAt).isNotNull()
        assertThat(result.activation.environmentKey).isEqualTo(env.id)
        assertThat(result.activation.versionKey).isEqualTo(draft.id)

        // No auto-created draft (on-demand lifecycle)
        val updatedVersions = ListVersions(variantId = variantId).query()
        assertThat(updatedVersions).hasSize(1)
        assertThat(updatedVersions.all { it.status == VersionStatus.PUBLISHED }).isTrue()
        Unit
    }

    @Test
    fun `publish already-published version to another environment creates second activation`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val variant = variants.first()
        val variantId = VariantId(variant.id, templateId)
        val stagingId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val staging = CreateEnvironment(id = stagingId, name = "Staging").execute()
        val productionId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val production = CreateEnvironment(id = productionId, name = "Production").execute()

        val versions = ListVersions(variantId = variantId).query()
        val draft = versions.first()
        val versionId = VersionId(draft.id, variantId)

        // Publish the contract first (guard rejects draft contracts)
        PublishContractVersion(templateId = templateId).execute()

        // Publish to staging first (this transitions draft -> published)
        val firstResult = PublishToEnvironment(
            versionId = versionId,
            environmentId = stagingId,
        ).execute()
        assertThat(firstResult).isNotNull

        // Publish same (now published) version to production
        val secondResult = PublishToEnvironment(
            versionId = versionId,
            environmentId = productionId,
        ).execute()

        assertThat(secondResult).isNotNull
        assertThat(secondResult!!.version.status).isEqualTo(VersionStatus.PUBLISHED)
        assertThat(secondResult.activation.environmentKey).isEqualTo(production.id)
        // No new draft (on-demand lifecycle)

        // Both activations should exist
        val activations = ListActivations(variantId = variantId).query()
        assertThat(activations).hasSize(2)
        assertThat(activations.map { it.environmentKey }).containsExactlyInAnyOrder(staging.id, production.id)
        Unit
    }

    @Test
    fun `publish to same environment is idempotent`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val variant = variants.first()
        val variantId = VariantId(variant.id, templateId)
        val environmentId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val env = CreateEnvironment(id = environmentId, name = "Staging").execute()

        val versions = ListVersions(variantId = variantId).query()
        val draft = versions.first()
        val versionId = VersionId(draft.id, variantId)

        // Publish the contract first (guard rejects draft contracts)
        PublishContractVersion(templateId = templateId).execute()

        // Publish twice to same environment
        val first = PublishToEnvironment(
            versionId = versionId,
            environmentId = environmentId,
        ).execute()
        val second = PublishToEnvironment(
            versionId = versionId,
            environmentId = environmentId,
        ).execute()

        assertThat(first).isNotNull
        assertThat(second).isNotNull
        assertThat(second!!.activation.versionKey).isEqualTo(first!!.activation.versionKey)
        // Only one activation should exist
        val activations = ListActivations(variantId = variantId).query()
        assertThat(activations).hasSize(1)
        Unit
    }

    @Test
    fun `publish to non-existent environment returns null`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val variant = variants.first()
        val variantId = VariantId(variant.id, templateId)

        val versions = ListVersions(variantId = variantId).query()
        val draft = versions.first()
        val versionId = VersionId(draft.id, variantId)

        // Publish the contract first (guard rejects draft contracts)
        PublishContractVersion(templateId = templateId).execute()

        val result = PublishToEnvironment(
            versionId = versionId,
            environmentId = EnvironmentId(EnvironmentKey.of("non-existent"), tenantId),
        ).execute()

        assertThat(result).isNull()
    }

    @Test
    fun `cannot publish archived version`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val variant = variants.first()
        val variantId = VariantId(variant.id, templateId)
        val stagingId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val staging = CreateEnvironment(id = stagingId, name = "Staging").execute()

        val versions = ListVersions(variantId = variantId).query()
        val draft = versions.first()
        val versionId = VersionId(draft.id, variantId)

        // Publish the contract first (guard rejects draft contracts)
        PublishContractVersion(templateId = templateId).execute()

        // Publish to staging, then remove activation, then archive
        PublishToEnvironment(
            versionId = versionId,
            environmentId = stagingId,
        ).execute()

        RemoveActivation(variantId = variantId, environmentId = stagingId).execute()

        ArchiveVersion(
            versionId = versionId,
        ).execute()

        // Try publishing archived version to another environment
        val anotherEnvId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val anotherEnv = CreateEnvironment(id = anotherEnvId, name = "Production").execute()
        val result = PublishToEnvironment(
            versionId = versionId,
            environmentId = anotherEnvId,
        ).execute()

        assertThat(result).isNull()
    }

    @Test
    fun `archive blocked when version is active`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val variant = variants.first()
        val variantId = VariantId(variant.id, templateId)
        val environmentId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val env = CreateEnvironment(id = environmentId, name = "Staging").execute()

        val versions = ListVersions(variantId = variantId).query()
        val draft = versions.first()
        val versionId = VersionId(draft.id, variantId)

        // Publish the contract first (guard rejects draft contracts)
        PublishContractVersion(templateId = templateId).execute()

        // Publish to staging
        PublishToEnvironment(
            versionId = versionId,
            environmentId = environmentId,
        ).execute()

        // Try to archive - should throw
        assertThatThrownBy {
            ArchiveVersion(
                versionId = versionId,
            ).execute()
        }.isInstanceOf(VersionStillActiveException::class.java)
            .hasMessageContaining("still active in environments")
        Unit
    }

    @Test
    fun `unpublish then archive succeeds`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variants = ListVariants(templateId = templateId).query()
        val variant = variants.first()
        val variantId = VariantId(variant.id, templateId)
        val environmentId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val env = CreateEnvironment(id = environmentId, name = "Staging").execute()

        val versions = ListVersions(variantId = variantId).query()
        val draft = versions.first()
        val versionId = VersionId(draft.id, variantId)

        // Publish the contract first (guard rejects draft contracts)
        PublishContractVersion(templateId = templateId).execute()

        // Publish, then remove activation, then archive
        PublishToEnvironment(
            versionId = versionId,
            environmentId = environmentId,
        ).execute()

        RemoveActivation(variantId = variantId, environmentId = environmentId).execute()

        val archived = ArchiveVersion(
            versionId = versionId,
        ).execute()

        assertThat(archived).isNotNull
        assertThat(archived!!.status).isEqualTo(VersionStatus.ARCHIVED)
        Unit
    }
}
