// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mcp.tools.AuthoringSchemaMcpTools
import app.epistola.suite.mcp.tools.DataContractMcpTools
import app.epistola.suite.mcp.tools.FontMcpTools
import app.epistola.suite.mcp.tools.ImageMcpTools
import app.epistola.suite.mcp.tools.StencilMcpTools
import app.epistola.suite.mcp.tools.TemplateMcpTools
import app.epistola.suite.mcp.tools.ThemeMcpTools
import app.epistola.suite.mcp.tools.VersionMcpTools
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/**
 * The MCP write tools: what an assistant needs to convert an existing document (a Word template,
 * say) into Epistola — templates, variants, drafts, data contracts, stencils, themes, images and
 * fonts. Each tool is called as the MCP transport calls it: a tenant-scoped API-key principal,
 * JSON arguments as strings, binaries as base64.
 */
class McpWriteToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var templateTools: TemplateMcpTools

    @Autowired private lateinit var versionTools: VersionMcpTools

    @Autowired private lateinit var contractTools: DataContractMcpTools

    @Autowired private lateinit var stencilTools: StencilMcpTools

    @Autowired private lateinit var themeTools: ThemeMcpTools

    @Autowired private lateinit var imageTools: ImageMcpTools

    @Autowired private lateinit var fontTools: FontMcpTools

    @Autowired private lateinit var schemaTools: AuthoringSchemaMcpTools

    @Autowired private lateinit var resourceLoader: ResourceLoader

    /** Like the API-key filter: a tenant-scoped service principal whose `users` row exists. */
    private fun <T> runAsApiKey(
        tenantId: TenantId,
        roles: Set<TenantRole> = TenantRole.entries.toSet(),
        block: () -> T,
    ): T = UUID.randomUUID().let { keyId ->
        runAs(
            EpistolaPrincipal(
                userId = UserKey.of(keyId),
                externalId = "apikey:$keyId",
                email = "apikey-$keyId@npa.epistola",
                displayName = "Test API Key",
                tenantMemberships = mapOf(tenantId.key to roles),
                globalRoles = emptySet(),
                platformRoles = emptySet(),
                currentTenantId = tenantId.key,
            ),
            block,
        )
    }

    private fun newTenant(): TenantId = TenantId(createTenant("MCP Write ${UUID.randomUUID()}").id)

    @Test
    fun `create_template then update_template_draft stores the document in the default variant`() {
        val tenantId = newTenant()

        val template = runAsApiKey(tenantId) {
            templateTools.createTemplate("default", "welcome-letter", "Welcome letter", null, null, null)
        }
        assertThat(template.id).isEqualTo("welcome-letter")
        val variant = runAsApiKey(tenantId) { templateTools.listVariants("default", "welcome-letter") }.single()

        val version = runAsApiKey(tenantId) {
            versionTools.updateTemplateDraft("default", "welcome-letter", variant.id, documentJson("Dear {{name}}"))
        }
        assertThat(version.status).isEqualTo("draft")

        // Called again, the same draft is replaced rather than a second one created.
        val again = runAsApiKey(tenantId) {
            versionTools.updateTemplateDraft("default", "welcome-letter", variant.id, documentJson("Hello {{name}}"))
        }
        assertThat(again.id).isEqualTo(version.id)
        val stored = runAsApiKey(tenantId) {
            GetEditorContext(variantId("default", tenantId, "welcome-letter", variant.id)).query()
        }!!
        assertThat(stored.templateModel.nodes["text1"]!!.props.toString()).contains("Hello {{name}}")
    }

    @Test
    fun `create_template sets the default variant's attributes`() {
        val tenantId = newTenant()

        runAsApiKey(tenantId) {
            templateTools.createTemplate("default", "brief", "Brief", null, null, """{"system.locale": "nl-NL"}""")
        }

        val variant = runAsApiKey(tenantId) { templateTools.listVariants("default", "brief") }.single()
        assertThat(variant.isDefault).isTrue
        assertThat(variant.attributes).isEqualTo(mapOf("system.locale" to "nl-NL"))
    }

    @Test
    fun `create_template with invalid variant attributes creates nothing`() {
        val tenantId = newTenant()

        assertThatThrownBy {
            runAsApiKey(tenantId) {
                templateTools.createTemplate("default", "brief", "Brief", null, null, """{"system.locale": "nl"}""")
            }
        }.hasMessageContaining("nl")

        assertThat(runAsApiKey(tenantId) { templateTools.listTemplates("default", null) }.map { it.id })
            .doesNotContain("brief")
    }

    @Test
    fun `update_template_draft reports a malformed document against the content argument`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) { templateTools.createTemplate("default", "broken", "Broken", null, null, null) }
        val variant = runAsApiKey(tenantId) { templateTools.listVariants("default", "broken") }.single()

        assertThatThrownBy {
            runAsApiKey(tenantId) { versionTools.updateTemplateDraft("default", "broken", variant.id, """{"nodes": 12}""") }
        }.hasMessageContaining("`content` is not a valid TemplateDocument")
    }

    @Test
    fun `create_variant and update_variant manage attributes`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) { templateTools.createTemplate("default", "letter", "Letter", null, null, null) }

        val created = runAsApiKey(tenantId) {
            templateTools.createVariant("default", "letter", "english", "English", "In English", """{"locale": "en-GB"}""")
        }
        assertThat(created.attributes).isEqualTo(mapOf("locale" to "en-GB"))

        val renamed = runAsApiKey(tenantId) {
            templateTools.updateVariant("default", "letter", "english", "English (UK)", null)
        }
        assertThat(renamed.title).isEqualTo("English (UK)")
        // Attributes were not given, so they are kept.
        assertThat(renamed.attributes).isEqualTo(mapOf("locale" to "en-GB"))

        val cleared = runAsApiKey(tenantId) { templateTools.updateVariant("default", "letter", "english", null, "{}") }
        assertThat(cleared.title).isEqualTo("English (UK)")
        assertThat(cleared.attributes).isEmpty()
    }

    @Test
    fun `update_variant on a missing variant names it`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) { templateTools.createTemplate("default", "letter", "Letter", null, null, null) }

        assertThatThrownBy {
            runAsApiKey(tenantId) { templateTools.updateVariant("default", "letter", "nope", "Nope", null) }
        }.isInstanceOf(NoSuchElementException::class.java).hasMessageContaining("default/letter/nope")
    }

    @Test
    fun `create_theme and update_template apply a theme with parsed styles`() {
        val tenantId = newTenant()

        val theme = runAsApiKey(tenantId) {
            themeTools.createTheme(
                catalogId = "default",
                themeId = "corporate",
                name = "Corporate",
                description = null,
                documentStyles = """{"fontSize": "10pt", "color": "#222222"}""",
                pageSettings = """{"format": "A4", "orientation": "portrait", "margins": {"top": 20, "right": 15, "bottom": 20, "left": 15}}""",
                blockStylePresets = """{"heading": {"label": "Heading", "styles": {"fontSize": "14pt"}}}""",
                spacingUnit = 4f,
            )
        }
        assertThat(theme.documentStyles).isEqualTo(mapOf("fontSize" to "10pt", "color" to "#222222"))
        assertThat(theme.pageSettings.toString()).contains("A4")
        assertThat((theme.blockStylePresets as Map<*, *>).keys).containsExactly("heading")

        val updated = runAsApiKey(tenantId) {
            themeTools.updateTheme("default", "corporate", "Corporate 2", null, """{"fontSize": "11pt"}""", null, null, null)
        }
        assertThat(updated.name).isEqualTo("Corporate 2")
        assertThat(updated.documentStyles).isEqualTo(mapOf("fontSize" to "11pt"))
        // Page settings were not given, so they are kept.
        assertThat(updated.pageSettings.toString()).contains("A4")

        runAsApiKey(tenantId) { templateTools.createTemplate("default", "invoice", "Invoice", "corporate", null, null) }
            .also { assertThat(it.themeId).isEqualTo("corporate") }
        val cleared = runAsApiKey(tenantId) {
            templateTools.updateTemplate("default", "invoice", null, null, null, true, null)
        }
        assertThat(cleared.themeId).isNull()
    }

    @Test
    fun `create_theme refuses a malformed styling argument by name`() {
        val tenantId = newTenant()

        assertThatThrownBy {
            runAsApiKey(tenantId) {
                themeTools.createTheme("default", "bad", "Bad", null, null, """{"format": 42}""", null, null)
            }
        }.hasMessageContaining("`pageSettings` is not a valid")
    }

    @Test
    fun `write tools refuse a SUBSCRIBED catalog`() {
        val tenantId = newTenant()

        assertThatThrownBy {
            runAsApiKey(tenantId) { themeTools.createTheme("system", "mine", "Mine", null, null, null, null, null) }
        }.hasMessageContaining("system")
    }

    @Test
    fun `write tools are refused to a key that can only view`() {
        val tenantId = newTenant()

        assertThatThrownBy {
            runAsApiKey(tenantId, roles = setOf(TenantRole.CONTENT_VIEWER)) {
                templateTools.createTemplate("default", "sneaky", "Sneaky", null, null, null)
            }
        }.hasMessageContaining("TEMPLATE_EDIT")
        val templates = runAsApiKey(tenantId) { templateTools.listTemplates("default", null) }
        assertThat(templates.map { it.id }).doesNotContain("sneaky")
    }

    @Test
    fun `update_data_contract sets the schema and examples and explains an example that does not fit`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) { templateTools.createTemplate("default", "letter", "Letter", null, null, null) }
        val schema = """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}"""

        val contract = runAsApiKey(tenantId) {
            contractTools.updateDataContract("default", "letter", schema, """[{"name": "Jane", "data": {"name": "Jane"}}]""")
        }
        assertThat(contract.status).isEqualTo("draft")
        assertThat(contract.dataModel.toString()).contains("\"name\"")
        assertThat(contract.dataExamples.map { it.id to it.name }).containsExactly("example-1" to "Jane")

        assertThatThrownBy {
            runAsApiKey(tenantId) {
                contractTools.updateDataContract("default", "letter", null, """[{"id": "bad", "name": "Bad", "data": {"name": 7}}]""")
            }
        }.hasMessageContaining("Data examples do not match the schema").hasMessageContaining("Bad: /name")
    }

    @Test
    fun `create_stencil and update_stencil_draft keep one draft and carry its parameter schema over`() {
        val tenantId = newTenant()
        val parameters = """{"type": "object", "properties": {"title": {"type": "string"}}}"""

        val stencil = runAsApiKey(tenantId) {
            stencilTools.createStencil("default", "letterhead", "Letterhead", null, listOf("header"), documentJson("ACME"), parameters)
        }
        assertThat(stencil.tags).containsExactly("header")

        val draft = runAsApiKey(tenantId) {
            stencilTools.updateStencilDraft("default", "letterhead", documentJson("ACME Corp"), null)
        }
        assertThat(draft.status).isEqualTo("draft")
        assertThat(draft.parameterSchema.toString()).contains("title")
        val versions = runAsApiKey(tenantId) { stencilTools.listStencilVersions("default", "letterhead") }
        assertThat(versions).hasSize(1)
        val content = runAsApiKey(tenantId) { stencilTools.getStencilVersion("default", "letterhead", draft.version) }!!
        assertThat(content.content!!.nodes["text1"]!!.props.toString()).contains("ACME Corp")

        val renamed = runAsApiKey(tenantId) { stencilTools.updateStencil("default", "letterhead", "Header", null, null) }
        assertThat(renamed.name).isEqualTo("Header")
        assertThat(renamed.tags).containsExactly("header")
    }

    @Test
    fun `upload_image stores a PNG with its dimensions and accepts a data URL`() {
        val tenantId = newTenant()
        val png = png(width = 3, height = 2)

        val image = runAsApiKey(tenantId) {
            imageTools.uploadImage("default", "logo.png", Base64.getEncoder().encodeToString(png), "image/png", "company-logo")
        }
        assertThat(image.id).isEqualTo("company-logo")
        assertThat(image.width to image.height).isEqualTo(3 to 2)
        assertThat(image.sizeBytes).isEqualTo(png.size.toLong())

        val fromDataUrl = runAsApiKey(tenantId) {
            imageTools.uploadImage("default", "logo2.png", "data:image/png;base64," + Base64.getEncoder().encodeToString(png), null, null)
        }
        assertThat(fromDataUrl.mediaType).isEqualTo("image/png")
    }

    @Test
    fun `upload_image refuses a font and content without a media type`() {
        val tenantId = newTenant()
        val bytes = Base64.getEncoder().encodeToString(png(1, 1))

        assertThatThrownBy {
            runAsApiKey(tenantId) { imageTools.uploadImage("default", "x", bytes, "font/ttf", null) }
        }.hasMessageContaining("upload_font")
        assertThatThrownBy {
            runAsApiKey(tenantId) { imageTools.uploadImage("default", "x", bytes, null, null) }
        }.hasMessageContaining("`mediaType` is required")
    }

    @Test
    fun `upload_font creates a family and replaces its faces on a second upload`() {
        val tenantId = newTenant()
        val regular = base64Font("inter-Regular.ttf")
        val bold = base64Font("inter-Bold.ttf")

        val font = runAsApiKey(tenantId) {
            fontTools.uploadFont("default", "brand-sans", "Brand Sans", "sans", """[{"weight": 400, "content": "$regular"}]""")
        }
        assertThat(font.catalog).isEqualTo("default")
        assertThat(font.variants.map { it.weight to it.italic }).containsExactly(400 to false)

        val replaced = runAsApiKey(tenantId) {
            fontTools.uploadFont(
                "default",
                "brand-sans",
                "Brand Sans",
                "sans",
                """[{"weight": 400, "content": "$regular"}, {"weight": 700, "italic": false, "content": "$bold"}]""",
            )
        }
        assertThat(replaced.variants.map { it.weight }).containsExactlyInAnyOrder(400, 700)
    }

    @Test
    fun `upload_font stores nothing when any face is not an embeddable font`() {
        val tenantId = newTenant()
        val regular = base64Font("inter-Regular.ttf")
        val notAFont = Base64.getEncoder().encodeToString("not a font".toByteArray())

        assertThatThrownBy {
            runAsApiKey(tenantId) {
                fontTools.uploadFont(
                    "default",
                    "broken",
                    "Broken",
                    "sans",
                    """[{"weight": 400, "content": "$regular"}, {"weight": 700, "content": "$notAFont"}]""",
                )
            }
        }.hasMessageContaining("Face 2")

        val fontAssets = runAsApiKey(tenantId) {
            ListAssets(tenantId = tenantId.key, catalogKey = CatalogKey.DEFAULT).query()
        }.filter { it.mediaType.category != AssetMediaCategory.IMAGE }
        assertThat(fontAssets).isEmpty()
        assertThat(runAsApiKey(tenantId) { fontTools.listFonts("default") }).isEmpty()
    }

    @Test
    fun `get_authoring_schemas returns the template document and theme schemas and the style registry`() {
        val tenantId = newTenant()

        val schemas = runAsApiKey(tenantId) { schemaTools.getAuthoringSchemas() }

        assertThat(schemas.templateDocument.get("\$id").asString()).endsWith("template-document.schema.json")
        assertThat(schemas.theme.get("\$id").asString()).endsWith("theme.schema.json")
        assertThat(schemas.shared.get("\$id").asString()).endsWith("template-shared.schema.json")
        assertThat(schemas.styleRegistry.get("groups").isArray).isTrue
    }

    private fun variantId(catalog: String, tenantId: TenantId, template: String, variant: String) = VariantId(
        VariantKey.of(variant),
        TemplateId(TemplateKey.of(template), CatalogId(CatalogKey.of(catalog), tenantId)),
    )

    /** A root holding one text node — the smallest document `update_template_draft` accepts. */
    private fun documentJson(text: String): String = """
        {
          "modelVersion": 1,
          "root": "root",
          "nodes": {
            "root": {"id": "root", "type": "root", "slots": ["slot-root"]},
            "text1": {"id": "text1", "type": "text", "slots": [], "props": {"content":
              {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "$text"}]}]}}}
          },
          "slots": {"slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": ["text1"]}},
          "themeRef": {"type": "inherit"}
        }
    """.trimIndent()

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    private fun base64Font(file: String): String = Base64.getEncoder().encodeToString(
        resourceLoader.getResource("classpath:epistola/fonts/inter/$file").contentAsByteArray,
    )
}
