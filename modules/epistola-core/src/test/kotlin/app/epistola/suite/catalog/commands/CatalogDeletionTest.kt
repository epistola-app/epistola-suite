package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.AssetInUseException
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogInUseException
import app.epistola.suite.catalog.CatalogKey
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
import app.epistola.suite.stencils.StencilInUseException
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CatalogDeletionTest : IntegrationTestBase() {

    @Nested
    inner class UnregisterCatalogTests {

        @Test
        fun `unregister with force=false and cross-references throws CatalogInUseException`() {
            val tenant = createTenant("Unreg Force False")
            val tenantId = TenantId(tenant.id)

            withMediator {
                // Create catalog A with a theme
                val catalogKeyA = CatalogKey.of("del-cat-a")
                val catalogIdA = CatalogId(catalogKeyA, tenantId)
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
                val catalogIdB = CatalogId(catalogKeyB, tenantId)
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
                    themeCatalogKey = catalogKeyA,
                ).execute()

                // Attempt unregister without force — should throw
                assertThatThrownBy {
                    UnregisterCatalog(
                        tenantKey = tenant.id,
                        catalogKey = catalogKeyA,
                        force = false,
                    ).execute()
                }.isInstanceOf(CatalogInUseException::class.java)
                    .hasMessageContaining("Dependent Template")
            }
        }

        @Test
        fun `unregister with force=true and cross-references succeeds`() {
            val tenant = createTenant("Unreg Force True")
            val tenantId = TenantId(tenant.id)

            withMediator {
                // Create catalog A with a theme
                val catalogKeyA = CatalogKey.of("force-cat-a")
                val catalogIdA = CatalogId(catalogKeyA, tenantId)
                CreateCatalog(
                    tenantKey = tenant.id,
                    id = catalogKeyA,
                    name = "Force Catalog A",
                ).execute()

                val themeKey = ThemeKey.of("force-theme")
                CreateTheme(
                    id = ThemeId(themeKey, catalogIdA),
                    name = "Force Theme",
                ).execute()

                // Create catalog B with a template referencing catalog A's theme
                val catalogKeyB = CatalogKey.of("force-cat-b")
                val catalogIdB = CatalogId(catalogKeyB, tenantId)
                CreateCatalog(
                    tenantKey = tenant.id,
                    id = catalogKeyB,
                    name = "Force Catalog B",
                ).execute()

                val templateKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(templateKey, catalogIdB)
                CreateDocumentTemplate(id = templateId, name = "Force Dependent").execute()
                UpdateDocumentTemplate(
                    id = templateId,
                    themeId = themeKey,
                    themeCatalogKey = catalogKeyA,
                ).execute()

                // Force unregister — should succeed
                val deleted = UnregisterCatalog(
                    tenantKey = tenant.id,
                    catalogKey = catalogKeyA,
                    force = true,
                ).execute()

                assertThat(deleted).isTrue()
            }
        }
    }

    @Nested
    inner class DeleteStencilTests {

        @Test
        fun `delete stencil with force=false when in use throws StencilInUseException`() {
            val tenant = createTenant("Del Stencil Block")
            val tenantId = TenantId(tenant.id)
            val catalogKey = CatalogKey.of("stencil-del-cat")
            val catalogId = CatalogId(catalogKey, tenantId)

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
                val variantKey = VariantKey.of("${templateKey.value}-default")
                val variantId = VariantId(variantKey, templateId)
                UpdateDraft(
                    variantId = variantId,
                    templateModel = buildTemplateModelWithStencil(stencilKey),
                ).execute()

                // Attempt delete without force
                assertThatThrownBy {
                    DeleteStencil(id = stencilId, force = false).execute()
                }.isInstanceOf(StencilInUseException::class.java)
                    .hasMessageContaining("Stencil Consumer")
            }
        }

        @Test
        fun `delete stencil with force=true when in use succeeds`() {
            val tenant = createTenant("Del Stencil Force")
            val tenantId = TenantId(tenant.id)
            val catalogKey = CatalogKey.of("stencil-force-cat")
            val catalogId = CatalogId(catalogKey, tenantId)

            withMediator {
                CreateCatalog(
                    tenantKey = tenant.id,
                    id = catalogKey,
                    name = "Stencil Force Catalog",
                ).execute()

                // Create a stencil
                val stencilKey = TestIdHelpers.nextStencilId()
                val stencilId = StencilId(stencilKey, catalogId)
                CreateStencil(id = stencilId, name = "Force Delete Stencil").execute()

                // Create a template that uses the stencil
                val templateKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(templateKey, catalogId)
                CreateDocumentTemplate(id = templateId, name = "Force Consumer").execute()
                val variantKey = VariantKey.of("${templateKey.value}-default")
                val variantId = VariantId(variantKey, templateId)
                UpdateDraft(
                    variantId = variantId,
                    templateModel = buildTemplateModelWithStencil(stencilKey),
                ).execute()

                // Force delete — should succeed
                val deleted = DeleteStencil(id = stencilId, force = true).execute()

                assertThat(deleted).isTrue()
            }
        }
    }

    @Nested
    inner class DeleteAssetTests {

        @Test
        fun `delete asset with force=false when in use throws AssetInUseException`() {
            withMediator {
                val tenant = createTenant("Del Asset Block")
                val tenantId = TenantId(tenant.id)

                val asset = UploadAsset(
                    tenantId = tenant.id,
                    name = "used-logo.png",
                    mediaType = AssetMediaType.PNG,
                    content = createMinimalPng(),
                    width = 1,
                    height = 1,
                    catalogKey = CatalogKey.DEFAULT,
                ).execute()

                // Create a template that uses the asset
                val templateKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
                CreateDocumentTemplate(id = templateId, name = "Asset Consumer").execute()
                val variantKey = VariantKey.of("${templateKey.value}-default")
                val variantId = VariantId(variantKey, templateId)
                UpdateDraft(
                    variantId = variantId,
                    templateModel = buildTemplateModelWithAsset(asset.id),
                ).execute()

                // Attempt delete without force
                assertThatThrownBy {
                    DeleteAsset(tenantId = tenant.id, assetId = asset.id, force = false).execute()
                }.isInstanceOf(AssetInUseException::class.java)
                    .hasMessageContaining("Asset Consumer")
            }
        }

        @Test
        fun `delete asset with force=true when in use succeeds`() {
            withMediator {
                val tenant = createTenant("Del Asset Force")
                val tenantId = TenantId(tenant.id)

                val asset = UploadAsset(
                    tenantId = tenant.id,
                    name = "force-logo.png",
                    mediaType = AssetMediaType.PNG,
                    content = createMinimalPng(),
                    width = 1,
                    height = 1,
                    catalogKey = CatalogKey.DEFAULT,
                ).execute()

                // Create a template that uses the asset
                val templateKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
                CreateDocumentTemplate(id = templateId, name = "Force Asset Consumer").execute()
                val variantKey = VariantKey.of("${templateKey.value}-default")
                val variantId = VariantId(variantKey, templateId)
                UpdateDraft(
                    variantId = variantId,
                    templateModel = buildTemplateModelWithAsset(asset.id),
                ).execute()

                // Force delete — should succeed
                val deleted = DeleteAsset(tenantId = tenant.id, assetId = asset.id, force = true).execute()

                assertThat(deleted).isTrue()
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
                    props = mapOf("assetId" to assetId.value.toString()),
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
