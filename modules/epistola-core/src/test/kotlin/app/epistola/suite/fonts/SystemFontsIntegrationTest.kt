package app.epistola.suite.fonts

import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.fonts.queries.GetFontVariantContent
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * End-to-end coverage for the bundled system fonts: every tenant gets the
 * eight OFL families seeded into its `system` catalog on creation, the
 * classpath-backed binaries resolve to real TTF bytes, and the structured
 * `fontFamily` ref is honoured by the live render path (the tenant default
 * theme now points at `inter`/`system`).
 */
@Timeout(60)
class SystemFontsIntegrationTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()

    private val expectedSlugs = listOf(
        "inter",
        "source-sans-3",
        "roboto",
        "lato",
        "source-serif-4",
        "merriweather",
        "lora",
        "jetbrains-mono",
    )

    /** A TrueType / OpenType collection signature, per the OpenType spec. */
    private fun ByteArray.isFontBinary(): Boolean {
        if (size < 4) return false
        val tag = String(copyOfRange(0, 4), Charsets.ISO_8859_1)
        val sfnt = ((this[0].toInt() and 0xFF) shl 24) or
            ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or
            (this[3].toInt() and 0xFF)
        return sfnt == 0x00010000 || tag == "true" || tag == "OTTO" || tag == "ttcf"
    }

    @Test
    fun `a new tenant has the eight system font families`() {
        val tenant = createTenant("Fonts1")
        withMediator {
            val fonts = ListFonts(TenantId(tenant.id), SYSTEM_CATALOG_KEY).query()
            assertThat(fonts).hasSize(8)
            assertThat(fonts.map { it.slug.value }).containsExactlyInAnyOrderElementsOf(expectedSlugs)
        }
    }

    /** The four canonical bundled faces: (weight, italic). */
    private val expectedFaces = listOf(
        400 to false,
        700 to false,
        400 to true,
        700 to true,
    )

    @Test
    fun `each family resolves four faces to real font binaries`() {
        val tenant = createTenant("Fonts2")
        withMediator {
            for (slug in expectedSlugs) {
                for ((weight, italic) in expectedFaces) {
                    val bytes = GetFontVariantContent(
                        tenantId = tenant.id,
                        catalogKey = SYSTEM_CATALOG_KEY,
                        slug = FontKey.of(slug),
                        weight = weight,
                        italic = italic,
                    ).query()
                    assertThat(bytes)
                        .withFailMessage("no bytes for $slug/$weight/$italic")
                        .isNotNull()
                    assertThat(bytes!!.size).isGreaterThan(10_000)
                    assertThat(bytes.isFontBinary())
                        .withFailMessage("$slug/$weight/$italic is not a TTF/OTF binary")
                        .isTrue()
                }
            }
        }
    }

    @Test
    fun `seeding is idempotent across repeated install`() {
        val tenant = createTenant("Fonts3")
        withMediator {
            // CreateTenant already ran EnsureSystemFonts once; run it again
            // directly — ImportFont UPSERTs, so the family count must hold.
            app.epistola.suite.fonts.commands.EnsureSystemFonts(tenant.id).execute()
            val fonts = ListFonts(TenantId(tenant.id), SYSTEM_CATALOG_KEY).query()
            assertThat(fonts).hasSize(8)
        }
    }

    @Test
    fun `live render embeds the referenced system font`() = scenario {
        given {
            val tenant = tenant("Fonts Render Tenant")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "Fonts Render Template")
            val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(compositeTemplateId, "Default")
            val compositeVariantId = VariantId(variant.id, compositeTemplateId)

            // Template: root → richTextVariable bound to "body". This renders
            // visible glyphs, which forces the resolved fontFamily (the tenant
            // default theme now references inter/system) to be embedded.
            val rootId = "root-fr"
            val rootSlot = "root-slot-fr"
            val rtvId = "rtv-fr"
            val templateModel = app.epistola.template.model.TemplateDocument(
                modelVersion = 1,
                root = rootId,
                nodes = mapOf(
                    rootId to app.epistola.template.model.Node(
                        id = rootId,
                        type = "root",
                        slots = listOf(rootSlot),
                    ),
                    rtvId to app.epistola.template.model.Node(
                        id = rtvId,
                        type = "richTextVariable",
                        slots = emptyList(),
                        props = mapOf("binding" to "body"),
                    ),
                ),
                slots = mapOf(
                    rootSlot to app.epistola.template.model.Slot(
                        id = rootSlot,
                        nodeId = rootId,
                        name = "children",
                        children = listOf(rtvId),
                    ),
                ),
                themeRef = app.epistola.template.model.ThemeRef.Inherit,
            )
            val version = version(compositeVariantId, templateModel)

            app.epistola.suite.templates.contracts.commands
                .UpdateContractVersion(
                    templateId = compositeTemplateId,
                    dataModel = objectMapper.readValue(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "body": { "${"$"}ref": "https://epistola.app/schemas/richtext-block-v1.json" }
                          }
                        }
                        """.trimIndent(),
                        ObjectNode::class.java,
                    ),
                ).execute()
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            val data = objectMapper.readValue(
                """
                {
                  "body": {
                    "type": "doc",
                    "content": [
                      {
                        "type": "paragraph",
                        "content": [{ "type": "text", "text": "Quick brown fox jumps." }]
                      }
                    ]
                  }
                }
                """.trimIndent(),
                ObjectNode::class.java,
            )
            query(
                PreviewVariant(
                    tenantId = setup.tenant.id,
                    catalogKey = CatalogKey.DEFAULT,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    data = data,
                ),
            )
        }.then { _, pdfBytes ->
            assertThat(pdfBytes).isNotEmpty()
            assertThat(String(pdfBytes.copyOfRange(0, 4))).isEqualTo("%PDF")
            // iText force-embeds resolver-provided faces as a subset whose
            // PostScript name retains the family ("ABCDEF+Inter-Regular").
            // The built-in Helvetica fallback would NOT put "Inter" in the
            // byte stream, so its presence proves the resolver fired.
            val raw = String(pdfBytes, Charsets.ISO_8859_1)
            assertThat(raw)
                .withFailMessage("rendered PDF does not embed the Inter system font")
                .contains("Inter")
        }
    }
}
