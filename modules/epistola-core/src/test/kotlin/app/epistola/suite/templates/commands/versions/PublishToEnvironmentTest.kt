package app.epistola.suite.templates.commands.versions

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.activations.RemoveActivation
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.activations.ListActivations
import app.epistola.suite.templates.queries.versions.ListVersions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PublishToEnvironmentTest : CoreIntegrationTestBase() {

    @Test
    fun `publish draft to environment marks it published and creates activation`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = app.epistola.suite.templates.queries.variants.ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val variant = variants.first()
        val env = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Staging").execute()

        // Get the draft version (auto-created with variant)
        val versions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        val draft = versions.first()
        assertThat(draft.status).isEqualTo(VersionStatus.DRAFT)

        val result = PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = env.id,
        ).execute()

        assertThat(result).isNotNull
        assertThat(result!!.version.status).isEqualTo(VersionStatus.PUBLISHED)
        assertThat(result.version.publishedAt).isNotNull()
        assertThat(result.activation.environmentId).isEqualTo(env.id)
        assertThat(result.activation.versionId).isEqualTo(draft.id)

        // Auto-created draft should exist
        assertThat(result.newDraft).isNotNull
        assertThat(result.newDraft!!.status).isEqualTo(VersionStatus.DRAFT)
        assertThat(result.newDraft!!.id.value).isEqualTo(draft.id.value + 1)

        // Variant should still have a draft
        val updatedVersions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        assertThat(updatedVersions).hasSize(2)
        assertThat(updatedVersions.any { it.status == VersionStatus.DRAFT }).isTrue()
        Unit
    }

    @Test
    fun `publish already-published version to another environment creates second activation`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = app.epistola.suite.templates.queries.variants.ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val variant = variants.first()
        val staging = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Staging").execute()
        val production = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Production").execute()

        val versions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        val draft = versions.first()

        // Publish to staging first (this transitions draft -> published)
        val firstResult = PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = staging.id,
        ).execute()
        assertThat(firstResult).isNotNull

        // Publish same (now published) version to production
        val secondResult = PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = production.id,
        ).execute()

        assertThat(secondResult).isNotNull
        assertThat(secondResult!!.version.status).isEqualTo(VersionStatus.PUBLISHED)
        assertThat(secondResult.activation.environmentId).isEqualTo(production.id)
        // No new draft when re-deploying an already-published version
        assertThat(secondResult.newDraft).isNull()

        // Both activations should exist
        val activations = ListActivations(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        assertThat(activations).hasSize(2)
        assertThat(activations.map { it.environmentId }).containsExactlyInAnyOrder(staging.id, production.id)
        Unit
    }

    @Test
    fun `publish to same environment is idempotent`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = app.epistola.suite.templates.queries.variants.ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val variant = variants.first()
        val env = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Staging").execute()

        val versions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        val draft = versions.first()

        // Publish twice to same environment
        val first = PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = env.id,
        ).execute()
        val second = PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = env.id,
        ).execute()

        assertThat(first).isNotNull
        assertThat(second).isNotNull
        assertThat(second!!.activation.versionId).isEqualTo(first!!.activation.versionId)
        // First publish creates a new draft, second doesn't
        assertThat(first.newDraft).isNotNull
        assertThat(second.newDraft).isNull()

        // Only one activation should exist
        val activations = ListActivations(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        assertThat(activations).hasSize(1)
        Unit
    }

    @Test
    fun `publish to non-existent environment returns null`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = app.epistola.suite.templates.queries.variants.ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val variant = variants.first()

        val versions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        val draft = versions.first()

        val result = PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = EnvironmentId.of("non-existent"),
        ).execute()

        assertThat(result).isNull()
    }

    @Test
    fun `cannot publish archived version`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = app.epistola.suite.templates.queries.variants.ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val variant = variants.first()
        val staging = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Staging").execute()

        val versions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        val draft = versions.first()

        // Publish to staging, then remove activation, then archive
        PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = staging.id,
        ).execute()

        RemoveActivation(tenantId = tenant.id, templateId = template.id, variantId = variant.id, environmentId = staging.id).execute()

        ArchiveVersion(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
        ).execute()

        // Try publishing archived version to another environment
        val anotherEnv = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Production").execute()
        val result = PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = anotherEnv.id,
        ).execute()

        assertThat(result).isNull()
    }

    @Test
    fun `archive blocked when version is active`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = app.epistola.suite.templates.queries.variants.ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val variant = variants.first()
        val env = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Staging").execute()

        val versions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        val draft = versions.first()

        // Publish to staging
        PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = env.id,
        ).execute()

        // Try to archive - should throw
        assertThatThrownBy {
            ArchiveVersion(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                versionId = draft.id,
            ).execute()
        }.isInstanceOf(VersionStillActiveException::class.java)
            .hasMessageContaining("still active in environments")
        Unit
    }

    @Test
    fun `unpublish then archive succeeds`(): Unit = withMediator {
        val tenant = createTenant("Test Tenant")
        val template = CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice").execute()
        val variants = app.epistola.suite.templates.queries.variants.ListVariants(tenantId = tenant.id, templateId = template.id).query()
        val variant = variants.first()
        val env = CreateEnvironment(id = TestIdHelpers.nextEnvironmentId(), tenantId = tenant.id, name = "Staging").execute()

        val versions = ListVersions(tenantId = tenant.id, templateId = template.id, variantId = variant.id).query()
        val draft = versions.first()

        // Publish, then remove activation, then archive
        PublishToEnvironment(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
            environmentId = env.id,
        ).execute()

        RemoveActivation(tenantId = tenant.id, templateId = template.id, variantId = variant.id, environmentId = env.id).execute()

        val archived = ArchiveVersion(
            tenantId = tenant.id,
            templateId = template.id,
            variantId = variant.id,
            versionId = draft.id,
        ).execute()

        assertThat(archived).isNotNull
        assertThat(archived!!.status).isEqualTo(VersionStatus.ARCHIVED)
        Unit
    }
}
