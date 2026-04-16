package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FindStencilUsagesTest : IntegrationTestBase() {

    @Test
    fun `finds template that uses a stencil`() {
        val tenant = createTenant("Stencil Usage Found")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-usage-cat")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "Stencil Usage Catalog",
            ).execute()

            // Create a stencil
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Header Stencil").execute()

            // Create a template that references the stencil
            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogId)
            CreateDocumentTemplate(id = templateId, name = "Letter Template").execute()
            val variantKey = VariantKey.of("${templateKey.value}-default")
            val variantId = VariantId(variantKey, templateId)
            UpdateDraft(
                variantId = variantId,
                templateModel = buildTemplateModelWithStencil(stencilKey),
            ).execute()

            // Query stencil usages
            val usages = FindStencilUsages(stencilId = stencilId).query()

            assertThat(usages).hasSize(1)
            assertThat(usages[0]).isEqualTo("Letter Template")
        }
    }

    @Test
    fun `returns empty when stencil is not used by any template`() {
        val tenant = createTenant("Stencil Not Used")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-unused-cat")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "Unused Stencil Catalog",
            ).execute()

            // Create a stencil but no template references it
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Orphan Stencil").execute()

            val usages = FindStencilUsages(stencilId = stencilId).query()

            assertThat(usages).isEmpty()
        }
    }

    @Test
    fun `returns empty when template exists but does not use the stencil`() {
        val tenant = createTenant("Stencil No Match")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-nomatch")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "No Match Catalog",
            ).execute()

            // Create a stencil
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Unused Header").execute()

            // Create a template WITHOUT the stencil (empty model)
            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogId)
            CreateDocumentTemplate(id = templateId, name = "Plain Template").execute()

            val usages = FindStencilUsages(stencilId = stencilId).query()

            assertThat(usages).isEmpty()
        }
    }

    @Test
    fun `returns multiple template names when stencil is used in several templates`() {
        val tenant = createTenant("Stencil Multi Use")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-multi")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "Multi Use Catalog",
            ).execute()

            // Create a stencil
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Shared Header").execute()

            // Create two templates both referencing the stencil
            val templateKey1 = TestIdHelpers.nextTemplateId()
            val templateId1 = TemplateId(templateKey1, catalogId)
            CreateDocumentTemplate(id = templateId1, name = "Invoice Template").execute()
            val variantKey1 = VariantKey.of("${templateKey1.value}-default")
            val variantId1 = VariantId(variantKey1, templateId1)
            UpdateDraft(
                variantId = variantId1,
                templateModel = buildTemplateModelWithStencil(stencilKey),
            ).execute()

            val templateKey2 = TestIdHelpers.nextTemplateId()
            val templateId2 = TemplateId(templateKey2, catalogId)
            CreateDocumentTemplate(id = templateId2, name = "Receipt Template").execute()
            val variantKey2 = VariantKey.of("${templateKey2.value}-default")
            val variantId2 = VariantId(variantKey2, templateId2)
            UpdateDraft(
                variantId = variantId2,
                templateModel = buildTemplateModelWithStencil(stencilKey),
            ).execute()

            val usages = FindStencilUsages(stencilId = stencilId).query()

            assertThat(usages).hasSize(2)
            assertThat(usages).containsExactlyInAnyOrder("Invoice Template", "Receipt Template")
        }
    }

    private fun buildTemplateModelWithStencil(stencilKey: StencilKey): TemplateDocument {
        val rootId = "root-1"
        val slotId = "slot-1"
        val stencilNodeId = "stencil-1"
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(
                rootId to Node(
                    id = rootId,
                    type = "root",
                    slots = listOf(slotId),
                ),
                stencilNodeId to Node(
                    id = stencilNodeId,
                    type = "stencil",
                    props = mapOf("stencilId" to stencilKey.value),
                ),
            ),
            slots = mapOf(
                slotId to Slot(
                    id = slotId,
                    nodeId = rootId,
                    name = "children",
                    children = listOf(stencilNodeId),
                ),
            ),
            themeRef = ThemeRef.Inherit,
        )
    }
}
