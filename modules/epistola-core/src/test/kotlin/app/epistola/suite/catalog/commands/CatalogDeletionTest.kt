// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogInUseException
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.movedTo
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.StencilInUseException
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testAddress
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.ThemeInUseException
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CatalogDeletionTest : IntegrationTestBase() {

    @Nested
    inner class DeleteCatalogTests {

        @Test
        fun `the default catalog is deleted like any other once nothing uses it`() {
            val tenant = createTenant("Delete Default")

            withMediator { DeleteCatalog(testCatalog(tenant.id, CatalogKey.of("default"))).execute() }

            assertThat(withMediator { GetCatalog(testCatalog(tenant.id, CatalogKey.of("default"))).query() }).isNull()
        }

        @Test
        fun `the system catalog cannot be deleted`() {
            val tenant = createTenant("Delete System")

            assertThatThrownBy { withMediator { DeleteCatalog(testCatalog(tenant.id, SYSTEM_CATALOG_KEY)).execute() } }
                .hasMessageContaining("The system catalog cannot be deleted")
            assertThat(withMediator { GetCatalog(testCatalog(tenant.id, SYSTEM_CATALOG_KEY)).query() }).isNotNull
        }

        @Test
        fun `unregister with cross-references throws CatalogInUseException`() {
            val tenant = createTenant("Unreg Force False")
            val tenantId = TenantId(tenant.id)

            withMediator {
                // Create catalog A with a theme
                val catalogKeyA = CatalogKey.of("del-cat-a")
                val catalogIdA = testCatalog(tenantId, catalogKeyA)
                CreateCatalog(
                    tenantKey = tenant.id,
                    id = catalogKeyA,
                    name = "Catalog A",
                ).execute()

                val themeKey = ThemeKey.of("del-theme")
                CreateTheme(
                    id = ThemeId(themeKey, catalogIdA),
                    name = "Deletable Theme",
                ).execute()

                // Create catalog B with a template referencing catalog A's theme
                val catalogKeyB = CatalogKey.of("del-cat-b")
                val catalogIdB = testCatalog(tenantId, catalogKeyB)
                CreateCatalog(
                    tenantKey = tenant.id,
                    id = catalogKeyB,
                    name = "Catalog B",
                ).execute()

                val templateKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(templateKey, catalogIdB)
                CreateDocumentTemplate(id = templateId, name = "Dependent Template").execute()
                UpdateDocumentTemplate(
                    id = templateId,
                    themeId = themeKey,
                    themeCatalog = testCatalog(templateId.tenantKey, catalogKeyA),
                ).execute()

                assertThatThrownBy {
                    DeleteCatalog(catalog = testCatalog(tenant.id, catalogKeyA)).execute()
                }.isInstanceOf(CatalogInUseException::class.java)
                    .hasMessageContaining("Dependent Template")
            }
        }
    }

    @Nested
    inner class DeleteStencilTests {

        @Test
        fun `delete stencil when in use throws StencilInUseException`() {
            val tenant = createTenant("Del Stencil Block")
            val tenantId = TenantId(tenant.id)
            val catalogKey = CatalogKey.of("stencil-del-cat")
            val catalogId = testCatalog(tenantId, catalogKey)

            withMediator {
                CreateCatalog(
                    tenantKey = tenant.id,
                    id = catalogKey,
                    name = "Stencil Deletion Catalog",
                ).execute()

                // Create a stencil
                val stencilKey = TestIdHelpers.nextStencilId()
                val stencilId = StencilId(stencilKey, catalogId)
                CreateStencil(id = stencilId, name = "Used Stencil").execute()

                // Create a template that uses the stencil
                val templateKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(templateKey, catalogId)
                CreateDocumentTemplate(id = templateId, name = "Stencil Consumer").execute()
                val variantKey = VariantKey.INITIAL
                val variantId = VariantId(variantKey, templateId)
                UpdateVariantContent(
                    variantId = variantId,
                    templateModel = buildTemplateModelWithStencil(stencilKey),
                ).execute()

                assertThatThrownBy {
                    DeleteStencil(id = stencilId).execute()
                }.isInstanceOf(StencilInUseException::class.java)
                    .hasMessageContaining("Stencil Consumer")
            }
        }
    }

    @Nested
    inner class DeleteThemeTests {

        @Test
        fun `a theme another catalog's template uses is not deleted`() {
            val tenant = createTenant("Del Theme Across")
            val (shared, letters) = listOf("shared", "letters").map { key ->
                withMediator { CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of(key), name = key).execute() }
                testCatalog(tenant.id, CatalogKey.of(key))
            }
            val theme = ThemeId(ThemeKey.of("brand"), shared)
            withMediator {
                CreateTheme(theme, "Brand").execute()
                val template = TemplateId(TestIdHelpers.nextTemplateId(), letters)
                CreateDocumentTemplate(template, "Branded Letter").execute()
                UpdateDocumentTemplate(template, themeId = theme.key, themeCatalog = shared).execute()
            }

            assertThatThrownBy { withMediator { DeleteTheme(theme).execute() } }
                .isInstanceOf(ThemeInUseException::class.java)
                .hasMessageContaining("Branded Letter")
        }

        @Test
        fun `a theme moved into another catalog is not deleted while the template it came from uses it`() {
            val tenant = createTenant("Del Theme Moved")
            val (shared, letters) = listOf("shared", "letters").map { key ->
                withMediator { CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of(key), name = key).execute() }
                testCatalog(tenant.id, CatalogKey.of(key))
            }
            withMediator {
                CreateTheme(ThemeId(ThemeKey.of("brand"), letters), "Brand").execute()
                val template = TemplateId(TestIdHelpers.nextTemplateId(), letters)
                CreateDocumentTemplate(template, "Branded Letter").execute()
                UpdateDocumentTemplate(template, themeId = ThemeKey.of("brand"), themeCatalog = letters).execute()
                val relocations = listOf(testAddress(tenant.id, CatalogResourceType.THEME, "letters", "brand").movedTo(shared.key))
                MoveCatalogResources(tenant.id, relocations, PreviewCatalogResourceMove(tenant.id, relocations).query().planFingerprint).execute()
            }

            assertThatThrownBy { withMediator { DeleteTheme(ThemeId(ThemeKey.of("brand"), shared)).execute() } }
                .isInstanceOf(ThemeInUseException::class.java)
                .hasMessageContaining("Branded Letter")
        }
    }

    @Nested
    inner class DeleteAssetTests {

        @Test
        fun `delete asset when in use throws AssetInUseException`() {
            withMediator {
                val tenant = createTenant("Del Asset Block")
                val tenantId = TenantId(tenant.id)

                val asset = UploadAsset(
                    catalog = testCatalog(tenant.id, CatalogKey.DEFAULT),
                    name = "used-logo.png",
                    mediaType = AssetMediaType.PNG,
                    content = createMinimalPng(),
                    width = 1,
                    height = 1,
                ).execute()

                // Create a template that uses the asset
                val templateKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
                CreateDocumentTemplate(id = templateId, name = "Asset Consumer").execute()
                val variantKey = VariantKey.INITIAL
                val variantId = VariantId(variantKey, templateId)
                UpdateVariantContent(
                    variantId = variantId,
                    templateModel = buildTemplateModelWithAsset(asset.id),
                ).execute()

                assertThatThrownBy {
                    DeleteAsset(tenantId = tenant.id, assetId = asset.id).execute()
                }.isInstanceOf(AssetInUseException::class.java)
                    .hasMessageContaining("Asset Consumer")
            }
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
                    props = mapOf(
                        "stencilKey" to stencilKey.value,
                    ),
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

    private fun buildTemplateModelWithAsset(assetId: AssetKey): TemplateDocument {
        val rootId = "root-1"
        val slotId = "slot-1"
        val imageNodeId = "image-1"
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(
                rootId to Node(
                    id = rootId,
                    type = "root",
                    slots = listOf(slotId),
                ),
                imageNodeId to Node(
                    id = imageNodeId,
                    type = "image",
                    props = mapOf("imageKey" to assetId.value),
                ),
            ),
            slots = mapOf(
                slotId to Slot(
                    id = slotId,
                    nodeId = rootId,
                    name = "children",
                    children = listOf(imageNodeId),
                ),
            ),
            themeRef = ThemeRef.Inherit,
        )
    }

    private fun createMinimalPng(): ByteArray {
        val header = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val ihdr = byteArrayOf(
            0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x01,
            0x08, 0x02,
            0x00, 0x00, 0x00,
            0x90.toByte(), 0x77, 0x53, 0xDE.toByte(),
        )
        val idat = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C,
            0x49, 0x44, 0x41, 0x54,
            0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(),
            0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
            0x00, 0x02, 0x00, 0x01,
            0xE2.toByte(), 0x21, 0xBC.toByte(), 0x33,
        )
        val iend = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        return header + ihdr + idat + iend
    }
}
