package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VersionScopingTest : IntegrationTestBase() {

    @Test
    fun `update version scopes by template key when variant and version ids collide`(): Unit = withMediator {
        val (_, firstVariantId, secondVariantId) = createTemplatesWithSharedVariant()
        val firstVersionId = VersionId(VersionKey.of(1), firstVariantId)
        val secondVersionId = VersionId(VersionKey.of(1), secondVariantId)

        UpdateDraft(secondVariantId, templateDocument("original-second")).execute()

        val updated = UpdateVersion(firstVersionId, templateDocument("updated-first")).execute()
        val second = GetVersion(secondVersionId).query()

        assertThat(updated!!.templateModel.root).isEqualTo("root-updated-first")
        assertThat(second!!.templateModel.root).isEqualTo("root-original-second")
    }

    @Test
    fun `archive version ignores active matching version in another template`(): Unit = withMediator {
        val (tenantId, firstVariantId, secondVariantId) = createTemplatesWithSharedVariant()
        val firstVersionId = VersionId(VersionKey.of(1), firstVariantId)
        val secondVersionId = VersionId(VersionKey.of(1), secondVariantId)
        val environmentId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        CreateEnvironment(id = environmentId, name = "Staging").execute()

        PublishContractVersion(firstVariantId.templateId).execute()
        PublishContractVersion(secondVariantId.templateId).execute()
        PublishVersion(firstVersionId).execute()
        PublishToEnvironment(secondVersionId, environmentId).execute()

        val archived = ArchiveVersion(firstVersionId).execute()
        val stillPublished = GetVersion(secondVersionId).query()

        assertThat(archived!!.status).isEqualTo(VersionStatus.ARCHIVED)
        assertThat(stillPublished!!.status).isEqualTo(VersionStatus.PUBLISHED)
    }

    private fun createTemplatesWithSharedVariant(): Triple<TenantId, VariantId, VariantId> {
        val tenant = createTenant("Version Scoping Test")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId(CatalogKey.DEFAULT, tenantId)
        val sharedVariantKey = TestIdHelpers.nextVariantId()
        val firstTemplateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        val secondTemplateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        val firstVariantId = VariantId(sharedVariantKey, firstTemplateId)
        val secondVariantId = VariantId(sharedVariantKey, secondTemplateId)

        CreateDocumentTemplate(id = firstTemplateId, name = "First Template").execute()
        CreateDocumentTemplate(id = secondTemplateId, name = "Second Template").execute()
        CreateVariant(id = firstVariantId, title = "Shared", description = null).execute()
        CreateVariant(id = secondVariantId, title = "Shared", description = null).execute()

        return Triple(tenantId, firstVariantId, secondVariantId)
    }

    private fun templateDocument(suffix: String): TemplateDocument {
        val rootId = "root-$suffix"
        val slotId = "slot-$suffix"
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(rootId to Node(id = rootId, type = "root", slots = listOf(slotId))),
            slots = mapOf(slotId to Slot(id = slotId, nodeId = rootId, name = "children", children = emptyList())),
            themeRef = ThemeRef.Inherit,
        )
    }
}
