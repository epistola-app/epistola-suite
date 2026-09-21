// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Exports one catalog holding every resource type and checks the v7 archive it produces.
 *
 * Two bugs lived here because nothing exported a font: every asset row became an `image` resource,
 * so a font family's face binaries were published as images whose media type is a font -- the exact
 * thing wire v7 stopped doing -- and the builder looked a face's bytes up under a key only the
 * *importer* mints, silently skipping when it missed. The second was invisible while the first
 * shipped the same bytes under an image resource.
 */
class CatalogWireExportTest : IntegrationTestBase() {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `a catalog with every resource type exports a v7 archive`() {
        val tenant = createTenant("Wire Inspection")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("town-hall")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey, catalogKey, "Town Hall", "Every resource type, for wire inspection").execute()

            // Image, named the way a person would.
            UploadAsset(
                tenantId = tenantKey,
                name = "Municipality mark",
                mediaType = AssetMediaType.PNG,
                content = PNG,
                width = 1,
                height = 1,
                catalogKey = catalogKey,
            ).execute()

            // Font faces: two weights, each its own binary in the assets table.
            val regular = UploadAsset(tenantKey, "Inter Regular", AssetMediaType.fromMimeType("font/ttf"), TTF, width = null, height = null, catalogKey = catalogKey).execute().id
            val bold = UploadAsset(tenantKey, "Inter Bold", AssetMediaType.fromMimeType("font/ttf"), TTF_BOLD, width = null, height = null, catalogKey = catalogKey).execute().id
            ImportFont(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = "inter",
                name = "Inter",
                kind = FontKind.SANS.wire,
                variants = listOf(
                    ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = regular),
                    ImportFontVariant(700, false, FontVariantSource.ASSET, assetKey = bold),
                ),
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), catalogId),
                displayName = "Language",
                allowedValues = listOf("nl", "en"),
            ).execute()

            CreateCodeList(
                id = CodeListId(CodeListKey.of("regions"), catalogId),
                displayName = "Regions",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("eu", "Europe"), CodeListEntry("us", "United States")),
            ).execute()

            CreateTheme(
                id = ThemeId(ThemeKey.of("house-style"), catalogId),
                name = "House Style",
                description = "The town hall's look",
                documentStyles = mapOf("fontFamily" to mapOf("slug" to "inter", "catalogKey" to catalogKey.value)),
            ).execute()

            val stencilId = StencilId(StencilKey.of("letterhead"), catalogId)
            CreateStencil(id = stencilId, name = "Letterhead", content = doc("letterhead-root")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()

            val templateId = TemplateId(TemplateKey.of("decision-letter"), catalogId)
            CreateDocumentTemplate(id = templateId, name = "Decision Letter").execute()
            UpdateDocumentTemplate(id = templateId, themeId = ThemeKey.of("house-style"), themeCatalogKey = catalogKey).execute()
            UpdateContractVersion(
                templateId = templateId,
                dataModel = JsonMapper().readValue(
                    """{"type":"object","properties":{"recipient":{"type":"string"}},"required":["recipient"]}""",
                    ObjectNode::class.java,
                ),
                dataExamples = listOf(DataExample("nl", "Dutch", JsonMapper().createObjectNode().put("recipient", "Ada"))),
            ).execute()
            PublishContractVersion(templateId = templateId).execute()
            PublishVersion(versionId = VersionId(VersionKey.of(1), VariantId(VariantKey.INITIAL, templateId))).execute()

            val zip = ExportCatalogZip(tenantKey, catalogKey).execute()
            val entries = ZipInputStream(ByteArrayInputStream(zip.zipBytes)).use { z ->
                generateSequence { z.nextEntry }.map { it.name }.toList()
            }

            // A font face's binary is not a catalog resource. Only the image is.
            assertThat(entries.filter { it.startsWith("resources/image/") }).hasSize(1)
            assertThat(entries).contains("resources/font/inter.json")

            // Every binary the archive names is placed by its hash, and is really there.
            val detail = readDetail(zip.zipBytes, "resources/font/inter.json")
            val font = detail.resource as FontResource
            assertThat(font.variants.map { it.mediaType }).containsOnly("font/ttf")
            for (face in font.variants) {
                assertThat(face.contentPath()).isEqualTo("bin/${face.contentHash}")
                assertThat(entries).contains(face.contentPath())
                // A v7 export places a binary by its hash rather than naming a path for it.
                assertThat(face.contentUrl).isNull()
            }

            val image = readDetail(zip.zipBytes, entries.single { it.startsWith("resources/image/") }).resource as ImageResource
            assertThat(entries).contains(image.contentPath())
            assertThat(image.contentUrl).isNull()
        }
    }

    private fun readDetail(zipBytes: ByteArray, path: String): ResourceDetail = ZipInputStream(ByteArrayInputStream(zipBytes)).use { z ->
        generateSequence { z.nextEntry }.first { it.name == path }
        objectMapper.readValue(z.readBytes(), ResourceDetail::class.java)
    }

    private fun doc(root: String) = TemplateDocument(
        modelVersion = 1,
        root = root,
        nodes = mapOf(root to Node(id = root, type = "root", slots = listOf("slot-$root"))),
        slots = mapOf("slot-$root" to Slot(id = "slot-$root", nodeId = root, name = "children", children = emptyList())),
        themeRef = ThemeRef.Inherit,
    )

    private companion object {
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
        )
        val TTF = byteArrayOf(0, 1, 0, 0, 0, 13, 0, -128, 0, 3, 0, 112)
        val TTF_BOLD = byteArrayOf(0, 1, 0, 0, 0, 13, 0, -128, 0, 3, 0, 113)
    }
}
