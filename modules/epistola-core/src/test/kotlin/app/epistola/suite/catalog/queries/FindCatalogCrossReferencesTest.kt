package app.epistola.suite.catalog.queries

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
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
import app.epistola.suite.stencils.commands.CreateStencil
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
import org.junit.jupiter.api.Test

class FindCatalogCrossReferencesTest : IntegrationTestBase() {

    @Test
    fun `detects template referencing theme from another catalog`() {
        val tenant = createTenant("Cross Ref Theme")
        val tenantId = TenantId(tenant.id)

        withMediator {
            // Create catalog A with a theme
            val catalogKeyA = CatalogKey.of("catalog-a")
            val catalogIdA = CatalogId(catalogKeyA, tenantId)
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKeyA,
                name = "Catalog A",
            ).execute()

            val themeKey = ThemeKey.of("shared-theme")
            CreateTheme(
                id = ThemeId(themeKey, catalogIdA),
                name = "Shared Theme",
            ).execute()

            // Create catalog B with a template that references the theme from catalog A
            val catalogKeyB = CatalogKey.of("catalog-b")
            val catalogIdB = CatalogId(catalogKeyB, tenantId)
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKeyB,
                name = "Catalog B",
            ).execute()

            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogIdB)
            CreateDocumentTemplate(id = templateId, name = "Cross-Ref Template").execute()

            // Set the template's theme to the one from catalog A (cross-catalog reference)
            UpdateDocumentTemplate(
                id = templateId,
                themeId = themeKey,
                themeCatalogKey = catalogKeyA,
            ).execute()

            // Query cross-references for catalog A
            val references = FindCatalogCrossReferences(
                tenantKey = tenant.id,
                catalogKey = catalogKeyA,
            ).query()

            assertThat(references).hasSize(1)
            assertThat(references[0]).contains("Cross-Ref Template")
            assertThat(references[0]).contains("catalog-b")
            assertThat(references[0]).contains("theme")
        }
    }

    @Test
    fun `detects template referencing stencil from another catalog`() {
        val tenant = createTenant("Cross Ref Stencil")
        val tenantId = TenantId(tenant.id)

        withMediator {
            // Create catalog A with a stencil
            val catalogKeyA = CatalogKey.of("stencil-cat-a")
            val catalogIdA = CatalogId(catalogKeyA, tenantId)
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKeyA,
                name = "Stencil Catalog A",
            ).execute()

            val stencilKey = StencilKey.of("shared-stencil")
            CreateStencil(
                id = StencilId(stencilKey, catalogIdA),
                name = "Shared Stencil",
            ).execute()

            // Create catalog B with a template that uses the stencil from catalog A
            val catalogKeyB = CatalogKey.of("stencil-cat-b")
            val catalogIdB = CatalogId(catalogKeyB, tenantId)
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKeyB,
                name = "Stencil Catalog B",
            ).execute()

            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogIdB)
            CreateDocumentTemplate(id = templateId, name = "Stencil User Template").execute()
            val variantKey = VariantKey.of("${templateKey.value}-default")
            val variantId = VariantId(variantKey, templateId)
            UpdateDraft(
                variantId = variantId,
                templateModel = buildTemplateModelWithStencil(stencilKey, catalogKeyA),
            ).execute()

            // Query cross-references for catalog A
            val references = FindCatalogCrossReferences(
                tenantKey = tenant.id,
                catalogKey = catalogKeyA,
            ).query()

            assertThat(references).isNotEmpty()
            assertThat(references).anyMatch { it.contains("Stencil User Template") && it.contains("stencil") }
        }
    }

    @Test
    fun `detects template referencing asset from another catalog`() {
        val tenant = createTenant("Cross Ref Asset")
        val tenantId = TenantId(tenant.id)

        withMediator {
            // Create catalog A with an asset
            val catalogKeyA = CatalogKey.of("asset-cat-a")
            val catalogIdA = CatalogId(catalogKeyA, tenantId)
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKeyA,
                name = "Asset Catalog A",
            ).execute()

            val asset = UploadAsset(
                tenantId = tenant.id,
                name = "shared-logo.png",
                mediaType = AssetMediaType.PNG,
                content = createMinimalPng(),
                width = 1,
                height = 1,
                catalogKey = catalogKeyA,
            ).execute()

            // Create catalog B with a template that uses the asset from catalog A
            val catalogKeyB = CatalogKey.of("asset-cat-b")
            val catalogIdB = CatalogId(catalogKeyB, tenantId)
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKeyB,
                name = "Asset Catalog B",
            ).execute()

            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogIdB)
            CreateDocumentTemplate(id = templateId, name = "Asset User Template").execute()
            val variantKey = VariantKey.of("${templateKey.value}-default")
            val variantId = VariantId(variantKey, templateId)
            UpdateDraft(
                variantId = variantId,
                templateModel = buildTemplateModelWithAsset(asset.id),
            ).execute()

            // Query cross-references for catalog A
            val references = FindCatalogCrossReferences(
                tenantKey = tenant.id,
                catalogKey = catalogKeyA,
            ).query()

            assertThat(references).isNotEmpty()
            assertThat(references).anyMatch { it.contains("Asset User Template") && it.contains("asset") }
        }
    }

    @Test
    fun `no false positives for same-catalog references`() {
        val tenant = createTenant("Same Catalog Ref")
        val tenantId = TenantId(tenant.id)

        withMediator {
            // Create a single catalog with a theme and a template referencing it
            val catalogKey = CatalogKey.of("self-contained")
            val catalogId = CatalogId(catalogKey, tenantId)
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "Self-Contained Catalog",
            ).execute()

            val themeKey = ThemeKey.of("internal-theme")
            CreateTheme(
                id = ThemeId(themeKey, catalogId),
                name = "Internal Theme",
            ).execute()

            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, catalogId)
            CreateDocumentTemplate(id = templateId, name = "Internal Template").execute()

            // Set theme from the SAME catalog
            UpdateDocumentTemplate(
                id = templateId,
                themeId = themeKey,
                themeCatalogKey = catalogKey,
            ).execute()

            // Query cross-references — should be empty since it's the same catalog
            val references = FindCatalogCrossReferences(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
            ).query()

            assertThat(references).isEmpty()
        }
    }

    @Test
    fun `empty result when no cross-references exist`() {
        val tenant = createTenant("No Cross Refs")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val catalogKey = CatalogKey.of("isolated-catalog")
            CreateCatalog(
                tenantKey = tenant.id,
                id = catalogKey,
                name = "Isolated Catalog",
            ).execute()

            val references = FindCatalogCrossReferences(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
            ).query()

            assertThat(references).isEmpty()
        }
    }

    private fun buildTemplateModelWithStencil(stencilKey: StencilKey, catalogKey: CatalogKey): TemplateDocument {
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
                        "stencilId" to stencilKey.value,
                        "catalogKey" to catalogKey.value,
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

    private fun buildTemplateModelWithAsset(assetId: app.epistola.suite.common.ids.AssetKey): TemplateDocument {
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
