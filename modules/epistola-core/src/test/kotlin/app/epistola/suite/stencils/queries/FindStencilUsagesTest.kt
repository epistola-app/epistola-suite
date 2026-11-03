// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.testCatalog
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FindStencilUsagesTest : IntegrationTestBase() {

    @Test
    fun `finds template that uses a stencil`() {
        val tenant = createTenant("Stencil Usage Found")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-usage-cat")
        val catalogId = testCatalog(tenantId, catalogKey)

        withMediator {
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "Stencil Usage Catalog",
            ).execute()

            // Create a stencil
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Header Stencil", catalogVersion = null).execute()

            // Create a template that references the stencil
            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogId)
            CreateDocumentTemplate(id = templateId, name = "Letter Template", catalogVersion = null).execute()
            val variantKey = VariantKey.INITIAL
            val variantId = VariantId(variantKey, templateId)
            UpdateVariantContent(
                variantId = variantId,
                templateModel = buildTemplateModelWithStencil(stencilKey),
                catalogVersion = null,
            ).execute()

            // Query stencil usages
            val usages = FindStencilUsages(stencilId = stencilId, catalogVersion = null).query()

            assertThat(usages).hasSize(1)
            assertThat(usages[0]).isEqualTo("Letter Template")
        }
    }

    @Test
    fun `returns empty when stencil is not used by any template`() {
        val tenant = createTenant("Stencil Not Used")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-unused-cat")
        val catalogId = testCatalog(tenantId, catalogKey)

        withMediator {
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "Unused Stencil Catalog",
            ).execute()

            // Create a stencil but no template references it
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Orphan Stencil", catalogVersion = null).execute()

            val usages = FindStencilUsages(stencilId = stencilId, catalogVersion = null).query()

            assertThat(usages).isEmpty()
        }
    }

    @Test
    fun `returns empty when template exists but does not use the stencil`() {
        val tenant = createTenant("Stencil No Match")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-nomatch")
        val catalogId = testCatalog(tenantId, catalogKey)

        withMediator {
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "No Match Catalog",
            ).execute()

            // Create a stencil
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Unused Header", catalogVersion = null).execute()

            // Create a template WITHOUT the stencil (empty model)
            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogId)
            CreateDocumentTemplate(id = templateId, name = "Plain Template", catalogVersion = null).execute()

            val usages = FindStencilUsages(stencilId = stencilId, catalogVersion = null).query()

            assertThat(usages).isEmpty()
        }
    }

    @Test
    fun `returns multiple template names when stencil is used in several templates`() {
        val tenant = createTenant("Stencil Multi Use")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("stencil-multi")
        val catalogId = testCatalog(tenantId, catalogKey)

        withMediator {
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "Multi Use Catalog",
            ).execute()

            // Create a stencil
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Shared Header", catalogVersion = null).execute()

            // Create two templates both referencing the stencil
            val templateKey1 = TestIdHelpers.nextTemplateId()
            val templateId1 = TemplateId(templateKey1, catalogId)
            CreateDocumentTemplate(id = templateId1, name = "Invoice Template", catalogVersion = null).execute()
            val variantKey1 = VariantKey.INITIAL
            val variantId1 = VariantId(variantKey1, templateId1)
            UpdateVariantContent(
                variantId = variantId1,
                templateModel = buildTemplateModelWithStencil(stencilKey),
                catalogVersion = null,
            ).execute()

            val templateKey2 = TestIdHelpers.nextTemplateId()
            val templateId2 = TemplateId(templateKey2, catalogId)
            CreateDocumentTemplate(id = templateId2, name = "Receipt Template", catalogVersion = null).execute()
            val variantKey2 = VariantKey.INITIAL
            val variantId2 = VariantId(variantKey2, templateId2)
            UpdateVariantContent(
                variantId = variantId2,
                templateModel = buildTemplateModelWithStencil(stencilKey),
                catalogVersion = null,
            ).execute()

            val usages = FindStencilUsages(stencilId = stencilId, catalogVersion = null).query()

            assertThat(usages).hasSize(2)
            assertThat(usages).containsExactlyInAnyOrder("Invoice Template", "Receipt Template")
        }
    }

    @Test
    fun `finds the stencils that contain it, of its own catalog and of another`() {
        val tenant = createTenant("Stencil In Stencils")
        val tenantId = TenantId(tenant.id)
        val own = testCatalog(tenantId, CatalogKey.of("parts"))
        val other = testCatalog(tenantId, CatalogKey.of("brand"))

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = own.key, name = "Parts").execute()
            CreateCatalog(tenantKey = tenant.id, id = other.key, name = "Brand").execute()
            val stencilKey = TestIdHelpers.nextStencilId()
            val stencilId = StencilId(stencilKey, own)
            CreateStencil(id = stencilId, name = "Seal", catalogVersion = null).execute()
            CreateStencil(id = StencilId(TestIdHelpers.nextStencilId(), own), name = "Frame", content = buildTemplateModelWithStencil(stencilKey), catalogVersion = null).execute()
            CreateStencil(id = StencilId(TestIdHelpers.nextStencilId(), other), name = "Letterhead", content = buildTemplateModelWithStencil(stencilKey, own.key.value), catalogVersion = null).execute()

            assertThat(FindStencilUsages(stencilId = stencilId, catalogVersion = null).query()).containsExactly("Frame", "Letterhead")
        }
    }

    private fun buildTemplateModelWithStencil(stencilKey: StencilKey, catalogKey: String? = null): TemplateDocument {
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
                    props = listOfNotNull("stencilKey" to stencilKey.value, catalogKey?.let { "catalogKey" to it }).toMap(),
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
