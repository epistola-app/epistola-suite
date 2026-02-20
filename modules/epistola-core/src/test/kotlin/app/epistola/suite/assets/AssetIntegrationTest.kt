package app.epistola.suite.assets

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.FindAssetUsages
import app.epistola.suite.assets.queries.GetAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AssetIntegrationTest : CoreIntegrationTestBase() {

    private val testPngBytes = createMinimalPng()

    @Test
    fun `upload and retrieve asset`() = withMediator {
        val tenant = createTenant("Test Tenant")

        val asset = UploadAsset(
            tenantId = tenant.id,
            name = "logo.png",
            mediaType = AssetMediaType.PNG,
            content = testPngBytes,
            width = 1,
            height = 1,
        ).execute()

        assertThat(asset.id).isNotNull()
        assertThat(asset.name).isEqualTo("logo.png")
        assertThat(asset.mediaType).isEqualTo(AssetMediaType.PNG)
        assertThat(asset.sizeBytes).isEqualTo(testPngBytes.size.toLong())
        assertThat(asset.width).isEqualTo(1)
        assertThat(asset.height).isEqualTo(1)
    }

    @Test
    fun `list assets returns uploaded assets in reverse chronological order`() = withMediator {
        val tenant = createTenant("Test Tenant")

        UploadAsset(tenant.id, "first.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()
        UploadAsset(tenant.id, "second.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

        val assets = ListAssets(tenantId = tenant.id).query()

        assertThat(assets).hasSize(2)
        assertThat(assets[0].name).isEqualTo("second.png")
        assertThat(assets[1].name).isEqualTo("first.png")
    }

    @Test
    fun `list assets with search filter`() = withMediator {
        val tenant = createTenant("Test Tenant")

        UploadAsset(tenant.id, "logo.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()
        UploadAsset(tenant.id, "banner.jpeg", AssetMediaType.JPEG, testPngBytes, 1, 1).execute()

        val results = ListAssets(tenantId = tenant.id, searchTerm = "logo").query()

        assertThat(results).hasSize(1)
        assertThat(results[0].name).isEqualTo("logo.png")
    }

    @Test
    fun `get asset metadata`() = withMediator {
        val tenant = createTenant("Test Tenant")
        val uploaded = UploadAsset(tenant.id, "test.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

        val asset = GetAsset(tenantId = tenant.id, assetId = uploaded.id).query()

        assertThat(asset).isNotNull
        assertThat(asset!!.name).isEqualTo("test.png")
        assertThat(asset.mediaType).isEqualTo(AssetMediaType.PNG)
    }

    @Test
    fun `get asset content`() = withMediator {
        val tenant = createTenant("Test Tenant")
        val uploaded = UploadAsset(tenant.id, "test.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

        val content = GetAssetContent(tenantId = tenant.id, assetId = uploaded.id).query()

        assertThat(content).isNotNull
        assertThat(content!!.content).isEqualTo(testPngBytes)
        assertThat(content.mediaType).isEqualTo(AssetMediaType.PNG)
    }

    @Test
    fun `get asset returns null for non-existent asset`() = withMediator {
        val tenant = createTenant("Test Tenant")

        val asset = GetAsset(tenantId = tenant.id, assetId = AssetId.generate()).query()

        assertThat(asset).isNull()
    }

    @Test
    fun `delete asset`() = withMediator {
        val tenant = createTenant("Test Tenant")
        val uploaded = UploadAsset(tenant.id, "test.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

        val deleted = DeleteAsset(tenantId = tenant.id, assetId = uploaded.id).execute()
        assertThat(deleted).isTrue()

        val asset = GetAsset(tenantId = tenant.id, assetId = uploaded.id).query()
        assertThat(asset).isNull()
    }

    @Test
    fun `delete returns false for non-existent asset`() = withMediator {
        val tenant = createTenant("Test Tenant")

        val deleted = DeleteAsset(tenantId = tenant.id, assetId = AssetId.generate()).execute()

        assertThat(deleted).isFalse()
    }

    @Test
    fun `tenant isolation - cannot see other tenant assets`() = withMediator {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        UploadAsset(tenant1.id, "tenant1-logo.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()
        UploadAsset(tenant2.id, "tenant2-logo.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

        val tenant1Assets = ListAssets(tenantId = tenant1.id).query()
        val tenant2Assets = ListAssets(tenantId = tenant2.id).query()

        assertThat(tenant1Assets).hasSize(1)
        assertThat(tenant1Assets[0].name).isEqualTo("tenant1-logo.png")
        assertThat(tenant2Assets).hasSize(1)
        assertThat(tenant2Assets[0].name).isEqualTo("tenant2-logo.png")
    }

    @Test
    fun `tenant isolation - cannot get other tenant asset content`() = withMediator {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        val uploaded = UploadAsset(tenant1.id, "secret.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

        val content = GetAssetContent(tenantId = tenant2.id, assetId = uploaded.id).query()

        assertThat(content).isNull()
    }

    @Test
    fun `upload rejects asset exceeding size limit`() {
        withMediator {
            val tenant = createTenant("Test Tenant")
            val oversizedContent = ByteArray(5 * 1024 * 1024 + 1) // 5MB + 1 byte

            assertThatThrownBy {
                UploadAsset(tenant.id, "huge.png", AssetMediaType.PNG, oversizedContent, 100, 100).execute()
            }.isInstanceOf(AssetTooLargeException::class.java)
        }
    }

    @Test
    fun `upload rejects blank name`() {
        withMediator {
            val tenant = createTenant("Test Tenant")

            assertThatThrownBy {
                UploadAsset(tenant.id, "  ", AssetMediaType.PNG, testPngBytes, 1, 1).execute()
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `upload SVG with null dimensions`() = withMediator {
        val tenant = createTenant("Test Tenant")
        val svgBytes = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect fill=\"red\" width=\"100\" height=\"100\"/></svg>".toByteArray()

        val asset = UploadAsset(
            tenantId = tenant.id,
            name = "icon.svg",
            mediaType = AssetMediaType.SVG,
            content = svgBytes,
            width = null,
            height = null,
        ).execute()

        assertThat(asset.width).isNull()
        assertThat(asset.height).isNull()
        assertThat(asset.mediaType).isEqualTo(AssetMediaType.SVG)
    }

    @Test
    fun `delete asset blocked when used in draft template version`() {
        withMediator {
            val tenant = createTenant("Test Tenant")
            val asset = UploadAsset(tenant.id, "header.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

            val templateId = TestIdHelpers.nextTemplateId()
            CreateDocumentTemplate(id = templateId, tenantId = tenant.id, name = "Invoice").execute()
            val variantId = VariantId.of("${templateId.value}-default")
            UpdateDraft(
                tenantId = tenant.id,
                templateId = templateId,
                variantId = variantId,
                templateModel = buildTemplateModelWithAsset(asset.id),
            ).execute()

            assertThatThrownBy {
                DeleteAsset(tenantId = tenant.id, assetId = asset.id).execute()
            }.isInstanceOf(AssetInUseException::class.java)
                .hasMessageContaining("Invoice")
        }
    }

    @Test
    fun `find asset usages returns template info for referenced asset`() = withMediator {
        val tenant = createTenant("Test Tenant")
        val asset = UploadAsset(tenant.id, "logo.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

        val templateId = TestIdHelpers.nextTemplateId()
        CreateDocumentTemplate(id = templateId, tenantId = tenant.id, name = "Welcome Letter").execute()
        val variantId = VariantId.of("${templateId.value}-default")
        UpdateDraft(
            tenantId = tenant.id,
            templateId = templateId,
            variantId = variantId,
            templateModel = buildTemplateModelWithAsset(asset.id),
        ).execute()

        val usages = FindAssetUsages(tenantId = tenant.id, assetId = asset.id).query()

        assertThat(usages).hasSize(1)
        assertThat(usages[0].templateName).isEqualTo("Welcome Letter")
    }

    @Test
    fun `find asset usages returns empty for unused asset`() = withMediator {
        val tenant = createTenant("Test Tenant")
        val asset = UploadAsset(tenant.id, "unused.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

        val usages = FindAssetUsages(tenantId = tenant.id, assetId = asset.id).query()

        assertThat(usages).isEmpty()
    }

    @Test
    fun `delete asset succeeds when asset removed from template`() = withMediator {
        val tenant = createTenant("Test Tenant")
        val asset = UploadAsset(tenant.id, "temp.png", AssetMediaType.PNG, testPngBytes, 1, 1).execute()

        val templateId = TestIdHelpers.nextTemplateId()
        CreateDocumentTemplate(id = templateId, tenantId = tenant.id, name = "Receipt").execute()
        val variantId = VariantId.of("${templateId.value}-default")
        UpdateDraft(
            tenantId = tenant.id,
            templateId = templateId,
            variantId = variantId,
            templateModel = buildTemplateModelWithAsset(asset.id),
        ).execute()

        // Remove asset from template by updating with empty model
        UpdateDraft(
            tenantId = tenant.id,
            templateId = templateId,
            variantId = variantId,
            templateModel = buildEmptyTemplateModel(),
        ).execute()

        val deleted = DeleteAsset(tenantId = tenant.id, assetId = asset.id).execute()
        assertThat(deleted).isTrue()
    }

    private fun buildTemplateModelWithAsset(assetId: AssetId): TemplateDocument {
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

    private fun buildEmptyTemplateModel(): TemplateDocument {
        val rootId = "root-1"
        val slotId = "slot-1"
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(
                rootId to Node(
                    id = rootId,
                    type = "root",
                    slots = listOf(slotId),
                ),
            ),
            slots = mapOf(
                slotId to Slot(
                    id = slotId,
                    nodeId = rootId,
                    name = "children",
                    children = emptyList(),
                ),
            ),
            themeRef = ThemeRef.Inherit,
        )
    }

    companion object {
        /** Minimal valid 1x1 PNG (67 bytes). */
        fun createMinimalPng(): ByteArray {
            val header = byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A, // PNG signature
            )
            val ihdr = byteArrayOf(
                0x00, 0x00, 0x00, 0x0D, // chunk length: 13
                0x49, 0x48, 0x44, 0x52, // "IHDR"
                0x00, 0x00, 0x00, 0x01, // width: 1
                0x00, 0x00, 0x00, 0x01, // height: 1
                0x08, 0x02, // bit depth: 8, color type: 2 (RGB)
                0x00, 0x00, 0x00, // compression, filter, interlace
                0x90.toByte(), 0x77, 0x53, 0xDE.toByte(), // CRC
            )
            val idat = byteArrayOf(
                0x00, 0x00, 0x00, 0x0C, // chunk length: 12
                0x49, 0x44, 0x41, 0x54, // "IDAT"
                0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(),
                0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
                0x00, 0x02, 0x00, 0x01, // compressed data
                0xE2.toByte(), 0x21, 0xBC.toByte(), 0x33, // CRC
            )
            val iend = byteArrayOf(
                0x00, 0x00, 0x00, 0x00, // chunk length: 0
                0x49, 0x45, 0x4E, 0x44, // "IEND"
                0xAE.toByte(), 0x42, 0x60, 0x82.toByte(), // CRC
            )
            return header + ihdr + idat + iend
        }
    }
}
