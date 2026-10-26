// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.io.font.FontProgramFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import tools.jackson.databind.ObjectMapper

/**
 * A template of catalog A inlines a stencil of catalog B whose text uses B's own font, named
 * relatively, as a stencil stores it (ADR 0033). Rendered from A's release and from its draft, the
 * text is drawn in B's font: the stencil's references mean its own catalog wherever it is inlined.
 */
class CrossCatalogStencilFontRenderTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val fontFile = "classpath:epistola/catalogs/system/binaries/fonts/inter/inter-Regular.ttf"

    private fun text(id: String, value: String, styles: Map<String, Any>? = null) = Node(
        id = id,
        type = "text",
        styles = styles,
        props = mapOf("content" to mapOf("type" to "doc", "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to value)))))),
    )

    private fun document(vararg nodes: Node) = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children"))) + nodes.associateBy { it.id },
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = nodes.map { it.id })) +
            nodes.flatMap { it.slots }.associateWith { Slot(id = it, nodeId = nodes.first { n -> it in n.slots }.id, name = "children", children = emptyList()) },
    )

    @Test
    fun `a stencil of another catalog draws in its own catalog's font, from a release and from a draft`() {
        val tenant = createTenant("Cross-catalog stencil font").id
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val parts = testCatalog(tenant, CatalogKey.of("parts"))
        val template = TemplateId(TemplateKey.of("invoice"), letters)
        val variant = VariantId(VariantKey.INITIAL, template)
        val bytes = resourceLoader.getResource(fontFile).contentAsByteArray
        withMediator {
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateCatalog(tenant, parts.key, "Parts").execute()
            ImportFont(parts, slug = "acme-sans", name = "Acme Sans", kind = FontKind.SANS.wire, variants = listOf(ImportFontVariant(400, false, "font/ttf", bytes))).execute()
            CreateStencil(
                StencilId(StencilKey.of("header"), parts),
                "Header",
                content = document(text("line", "PARTS HEADER", styles = mapOf("fontFamily" to mapOf("key" to "acme-sans", "catalogKey" to "parts")))),
            ).execute()
            mediator.releaseNext(parts)
            CreateDocumentTemplate(template, "Invoice").execute()
            UpdateVariantContent(
                variant,
                document(Node(id = "header", type = "stencil", slots = listOf("header-slot"), props = mapOf("stencilKey" to "header", "catalogKey" to "parts"))),
            ).execute()
            mediator.publishAndRelease(variant)
        }

        val expected = FontProgramFactory.createFont(bytes, false).fontNames.fontName
        for (workingCopy in listOf(false, true)) {
            val pdf = withMediator {
                PreviewDocument(catalog = letters, templateId = template.key, data = objectMapper.createObjectNode(), variantId = variant.key, workingCopy = workingCopy).query()
            }
            assertThat(fontsOf(pdf, "PARTS HEADER")).`as`(if (workingCopy) "the draft" else "the release").containsExactly(expected)
        }
    }

    private fun fontsOf(pdf: ByteArray, text: String): Set<String> {
        val fonts = mutableSetOf<String>()
        PdfDocument(PdfReader(pdf.inputStream())).use { document ->
            val listener = object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (type != EventType.RENDER_TEXT) return
                    val info = data as TextRenderInfo
                    if (info.text.isNotBlank() && text.contains(info.text.trim())) fonts += info.font.fontProgram.fontNames.fontName.substringAfter('+')
                }

                override fun getSupportedEvents(): Set<EventType>? = null
            }
            for (page in 1..document.numberOfPages) PdfCanvasProcessor(listener).processPageContent(document.getPage(page))
        }
        return fonts
    }
}
