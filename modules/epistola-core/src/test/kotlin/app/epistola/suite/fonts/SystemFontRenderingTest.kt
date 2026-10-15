// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.generation.pdf.DirectPdfRenderer
import app.epistola.generation.pdf.FontFamilyResolver
import app.epistola.generation.pdf.ResolvedTheme
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.fonts.commands.EnsureSystemFontsHandler
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.io.font.FontProgramFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Renders every bundled system font family, in each of its four faces, into every place text can
 * sit: page header, page footer, address window, the address block's aside and the body. Each must
 * be drawn in the selected face, and a PDF/A render must embed every font. Until this existed the
 * renderer was only ever tested with Liberation Sans, so a font that failed in one region (#1097)
 * or a file holding the wrong face went unnoticed.
 *
 * A parsed face holds its kerning tables (Inter about 25 MB a face), so the families render one
 * after another, keeping this test inside the 512 MB unit-test heap it shares with the module's
 * ArchUnit scans.
 */
@Execution(ExecutionMode.SAME_THREAD)
class SystemFontRenderingTest {

    private val faces = EnsureSystemFontsHandler.DECLARED_FACES

    private val resolver = FontFamilyResolver { _, slug, weight, italic ->
        faces.firstOrNull { it.slug == slug && it.weight == weight && it.italic == italic }
            ?.let { face -> javaClass.classLoader.getResourceAsStream(face.classpathLocation)!!.use { it.readBytes() } }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("families")
    fun `every region renders in the selected face, embedded under PDF A`(slug: String) {
        val theme = ResolvedTheme(documentStyles = mapOf("fontFamily" to mapOf("slug" to slug, "catalogKey" to SYSTEM_CATALOG_KEY.value)))
        val failures = mutableListOf<String>()

        for (face in faces.filter { it.slug == slug }) {
            val expectedFont = javaClass.classLoader.getResourceAsStream(face.classpathLocation)!!
                .use { FontProgramFactory.createFont(it.readBytes(), false).fontNames.fontName }
            val marks = listOfNotNull("bold".takeIf { face.weight >= 700 }, "italic".takeIf { face.italic })

            for (pdfa in listOf(true, false)) {
                val label = "${face.weight}/italic=${face.italic}/pdfa=$pdfa"
                val bytes = try {
                    ByteArrayOutputStream().also {
                        DirectPdfRenderer().render(
                            document(marks),
                            emptyMap(),
                            it,
                            resolvedTheme = theme,
                            pdfaCompliant = pdfa,
                            fontFamilyResolver = resolver,
                        )
                    }.toByteArray()
                } catch (e: Exception) {
                    failures += "$label: render failed: ${e.message}"
                    continue
                }

                val fontsByRegion = fontsByRegion(bytes)
                for (region in REGIONS) {
                    val used = fontsByRegion[region]
                    if (used != setOf(expectedFont)) failures += "$label: $region drawn in $used, expected $expectedFont"
                }
                if (pdfa) nonEmbeddedFonts(bytes).forEach { failures += "$label: non-embedded font $it" }
            }
        }

        assertThat(failures).isEmpty()
    }

    private fun textNode(id: String, text: String, marks: List<String>) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf(
                        "type" to "paragraph",
                        "content" to listOf(mapOf("type" to "text", "text" to text, "marks" to marks.map { mapOf("type" to it) })),
                    ),
                ),
            ),
        ),
    )

    private fun document(marks: List<String>) = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
            "header" to Node(id = "header", type = "pageheader", slots = listOf("slot-header"), props = mapOf("height" to "30pt")),
            "header-text" to textNode("header-text", "HEADER $SAMPLE", marks),
            "footer" to Node(id = "footer", type = "pagefooter", slots = listOf("slot-footer"), props = mapOf("height" to "30pt")),
            "footer-text" to textNode("footer-text", "FOOTER $SAMPLE", marks),
            "addressblock" to Node(
                id = "addressblock",
                type = "addressblock",
                slots = listOf("slot-address", "slot-aside"),
                props = mapOf("top" to 45, "sideDistance" to 20, "addressWidth" to 85, "height" to 45),
            ),
            "address-text" to textNode("address-text", "ADDRESS $SAMPLE", marks),
            "aside-text" to textNode("aside-text", "ASIDE $SAMPLE", marks),
            "body-text" to textNode("body-text", "BODY $SAMPLE", marks),
        ),
        slots = mapOf(
            "slot-root" to Slot(
                id = "slot-root",
                nodeId = "root",
                name = "children",
                children = listOf("header", "footer", "addressblock", "body-text"),
            ),
            "slot-header" to Slot(id = "slot-header", nodeId = "header", name = "children", children = listOf("header-text")),
            "slot-footer" to Slot(id = "slot-footer", nodeId = "footer", name = "children", children = listOf("footer-text")),
            "slot-address" to Slot(id = "slot-address", nodeId = "addressblock", name = "address", children = listOf("address-text")),
            "slot-aside" to Slot(id = "slot-aside", nodeId = "addressblock", name = "aside", children = listOf("aside-text")),
        ),
    )

    /** The fonts each region's text is drawn in, keyed by the marker word the region's text starts with. */
    private fun fontsByRegion(bytes: ByteArray): Map<String, Set<String>> {
        val fonts = mutableMapOf<String, MutableSet<String>>()
        PdfDocument(PdfReader(ByteArrayInputStream(bytes))).use { pdf ->
            val listener = object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (type != EventType.RENDER_TEXT) return
                    val info = data as TextRenderInfo
                    val region = REGIONS.firstOrNull { info.text.trimStart().startsWith(it) } ?: return
                    fonts.getOrPut(region) { mutableSetOf() } += info.font.fontProgram.fontNames.fontName.substringAfter('+')
                }

                override fun getSupportedEvents(): Set<EventType>? = null
            }
            for (page in 1..pdf.numberOfPages) PdfCanvasProcessor(listener).processPageContent(pdf.getPage(page))
        }
        return fonts
    }

    private fun nonEmbeddedFonts(bytes: ByteArray): List<String> = PdfDocument(PdfReader(ByteArrayInputStream(bytes))).use { pdf ->
        (1..pdf.numberOfPages).flatMap { pageNumber ->
            val fonts = pdf.getPage(pageNumber).resources.getResource(PdfName.Font) ?: return@flatMap emptyList()
            fonts.keySet().mapNotNull { key ->
                val font = fonts.getAsDictionary(key)
                val descriptor = font.getAsDictionary(PdfName.FontDescriptor)
                    ?: font.getAsArray(PdfName.DescendantFonts)?.getAsDictionary(0)?.getAsDictionary(PdfName.FontDescriptor)
                val embedded = descriptor != null &&
                    listOf(PdfName.FontFile, PdfName.FontFile2, PdfName.FontFile3).any(descriptor::containsKey)
                if (embedded) null else "${font.getAsName(PdfName.BaseFont)} on page $pageNumber"
            }
        }
    }

    companion object {
        /** Characters every bundled family covers (see [SystemFontFilesTest]). */
        private const val SAMPLE = "José Gómez, Zoë Müller, Straße, Ça va, ‘kenmerk’ “x” – € 10"

        private val REGIONS = listOf("HEADER", "FOOTER", "ADDRESS", "ASIDE", "BODY")

        @JvmStatic
        fun families(): List<String> = EnsureSystemFontsHandler.DECLARED_FACES.map { it.slug }.distinct()
    }
}
