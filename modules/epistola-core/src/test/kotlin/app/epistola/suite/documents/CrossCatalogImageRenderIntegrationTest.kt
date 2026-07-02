package app.epistola.suite.documents

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
import tools.jackson.databind.ObjectMapper

/**
 * End-to-end cover for the cross-catalog image feature (#555): a template whose
 * image node references an asset living in ANOTHER catalog must resolve and
 * render through the real preview path.
 *
 * The unit tests around this feature each cover one link in isolation
 * (`ImageNodeRendererTest` forwards the `catalogKey` to a *stub* resolver;
 * `AssetIntegrationTest` queries `GetAssetContent` *directly*). This test closes
 * the seam between them — the resolver lambda in `DocumentPreviewRenderer` that
 * reads the node's `catalogKey` and queries `GetAssetContent` — by rendering an
 * actual PDF and proving the cross-catalog byte resolution happened.
 *
 * The `catalogKey` is made load-bearing by contrast: a *wrong* catalog key does
 * not resolve (the lookup is qualified by catalog), so the image is skipped and
 * the PDF is smaller. If the render path dropped the `catalogKey`, both cases
 * would produce the same PDF and the second assertion would fail.
 */
class CrossCatalogImageRenderIntegrationTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()
    private val testPngBytes = createMinimalPng()

    @Test
    fun `preview resolves and renders an image whose asset lives in another catalog`(): Unit = withMediator {
        val tenant = createTenant("Cross-Catalog Render")
        val tenantId = TenantId(tenant.id)

        // The asset lives in a SEPARATE catalog, not the template's own catalog.
        val assetCatalogKey = CatalogKey.of("xcat-image-store")
        CreateCatalog(tenantKey = tenant.id, id = assetCatalogKey, name = "Cross-Catalog Image Store").execute()
        val asset = UploadAsset(
            tenantId = tenant.id,
            name = "shared-badge.png",
            mediaType = AssetMediaType.PNG,
            content = testPngBytes,
            width = 1,
            height = 1,
            catalogKey = assetCatalogKey,
        ).execute()

        // The template lives in the tenant's DEFAULT catalog and embeds that
        // cross-catalog asset, qualified by its owning catalog key.
        val withImage = renderPreview(
            tenant.id,
            tenantId,
            imageProps = mapOf(
                "assetId" to asset.id.value.toString(),
                "catalogKey" to assetCatalogKey.value,
                "alt" to "Shared badge",
                "width" to "24pt",
                "height" to "24pt",
            ),
        )

        // Same template, but the image points at a catalog the asset is NOT in.
        // The catalog-qualified lookup finds nothing, so the image is dropped.
        val wrongCatalog = renderPreview(
            tenant.id,
            tenantId,
            imageProps = mapOf(
                "assetId" to asset.id.value.toString(),
                "catalogKey" to "no-such-catalog",
                "alt" to "Shared badge",
                "width" to "24pt",
                "height" to "24pt",
            ),
        )

        assertThat(String(withImage.copyOfRange(0, 4))).isEqualTo("%PDF")
        assertThat(String(wrongCatalog.copyOfRange(0, 4))).isEqualTo("%PDF")
        // The cross-catalog asset actually landed in the PDF: the resolved-image
        // render is meaningfully larger than the one where it couldn't resolve.
        assertThat(withImage.size)
            .withFailMessage(
                "cross-catalog image did not resolve into the PDF " +
                    "(resolved=${withImage.size}B vs unresolved=${wrongCatalog.size}B)",
            )
            .isGreaterThan(wrongCatalog.size)
    }

    /** Publishes a fresh template with a single image node and returns its preview PDF. */
    private fun renderPreview(
        tenantKey: app.epistola.suite.common.ids.TenantKey,
        tenantId: TenantId,
        imageProps: Map<String, Any?>,
    ): ByteArray {
        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Cross-Catalog Image Template").execute()

        val variantId = VariantId(VariantKey.INITIAL, templateId)
        UpdateDraft(
            variantId = variantId,
            templateModel = TemplateDocument(
                modelVersion = 1,
                root = "root",
                nodes = mapOf(
                    "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                    "img" to Node(id = "img", type = "image", slots = emptyList(), props = imageProps),
                ),
                slots = mapOf(
                    "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("img")),
                ),
                themeRef = ThemeRef.Inherit,
            ),
        ).execute()

        return PreviewVariant(
            tenantId = tenantKey,
            catalogKey = CatalogKey.DEFAULT,
            templateId = templateKey,
            variantId = VariantKey.INITIAL,
            data = objectMapper.createObjectNode(),
        ).query()
    }

    /** Minimal valid 1×1 PNG (67 bytes) — the same fixture the asset tests use. */
    private fun createMinimalPng(): ByteArray {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
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
