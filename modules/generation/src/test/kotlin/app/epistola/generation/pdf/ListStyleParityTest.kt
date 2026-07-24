// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for issue #401: a list authored inside a `text` component
 * (ProseMirror `bullet_list` / `ordered_list`) and a `datalist` component must
 * render with the same marker glyph and spacing in PDF.
 *
 * Both paths read their list margins and bullet markers from the shared
 * [RenderingDefaults]; this test renders a template that contains BOTH list
 * flavours and asserts the two render identically.
 */
class ListStyleParityTest {

    private val renderer = DirectPdfRenderer()
    private val items = listOf("Alpha", "Beta", "Gamma")

    private fun renderToPdf(
        doc: TemplateDocument,
        data: Map<String, Any?>,
        fontFamilyResolver: FontFamilyResolver? = null,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        renderer.render(doc, data, output, fontFamilyResolver = fontFamilyResolver)
        return output.toByteArray()
    }

    /** A `text` component whose content is a single ProseMirror list of [items]. */
    private fun textListNode(id: String, listType: String, bulletStyle: String, fontFamily: Map<String, Any>?): Node {
        val listNodeType = if (listType == "bullet") "bulletList" else "orderedList"
        val attrs = if (listType == "bullet") {
            mapOf("listStyle" to bulletStyle)
        } else {
            mapOf("listType" to listType)
        }
        val listItems = items.map { text ->
            mapOf(
                "type" to "listItem",
                "content" to listOf(
                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text))),
                ),
            )
        }
        return Node(
            id = id,
            type = "text",
            styles = fontFamily?.let { mapOf("fontFamily" to it) },
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(mapOf("type" to listNodeType, "attrs" to attrs, "content" to listItems)),
                ),
            ),
        )
    }

    /** A document with a text-component list followed by a datalist, both rendering [items]. */
    private fun documentWithBothLists(
        listType: String,
        bulletStyle: String = "disc",
        fontFamily: Map<String, Any>? = null,
    ): Pair<TemplateDocument, Map<String, Any?>> {
        val rootSlotId = "slot-root"
        val itemSlotId = "slot-item"
        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "text-list" to textListNode("text-list", listType, bulletStyle, fontFamily),
                "datalist" to Node(
                    id = "datalist",
                    type = "datalist",
                    styles = fontFamily?.let { mapOf("fontFamily" to it) },
                    slots = listOf(itemSlotId),
                    props = mapOf(
                        "expression" to mapOf("raw" to "items", "language" to "simple_path"),
                        "itemAlias" to "item",
                        "listType" to listType,
                        "bulletStyle" to bulletStyle,
                    ),
                ),
                "item-text" to Node(
                    id = "item-text",
                    type = "text",
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(mapOf("type" to "text", "text" to "{{item}}")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = "root",
                    name = "children",
                    children = listOf("text-list", "datalist"),
                ),
                itemSlotId to Slot(
                    id = itemSlotId,
                    nodeId = "datalist",
                    name = "item-template",
                    children = listOf("item-text"),
                ),
            ),
        )
        return doc to mapOf("items" to items)
    }

    @Test
    fun `numbered list from text and datalist render identical markers and text`() {
        assertListPathsAgree("decimal")
    }

    @Test
    fun `every bullet style renders identically from text and datalist`() {
        // The datalist `bulletStyle` prop mirrors the ProseMirror `bullet_list` `listStyle`
        // attribute; both feed RenderingDefaults.bulletMarker(style), so the two paths must be
        // indistinguishable for every style — the core #401 requirement.
        for (style in listOf("disc", "circle", "square", "dash")) {
            assertListPathsAgree("bullet", style)
        }
    }

    @Test
    fun `all bullet glyphs survive to the PDF even with no font configured`() {
        // The critical real-world case (#401): NO font family is configured, so the content font
        // is iText's standard WinAnsi Helvetica, which lacks circle (U+25CB) / square (U+25A0).
        // disc (U+2022) / dash (U+2013) are WinAnsi-safe; circle / square fall back to the bundled
        // Liberation Sans (which carries them) via FontCache.fontCoveringOrFallback. All four must
        // therefore appear in the output for BOTH the text component and the datalist.
        for ((style, glyph) in mapOf("disc" to "•", "dash" to "–", "circle" to "○", "square" to "■")) {
            val rendered = assertListPathsAgree("bullet", style)
            assertEquals(
                items.size * 2,
                countOccurrences(rendered, glyph),
                "bullet style '$style' should render its marker glyph '$glyph' once per item in both lists",
            )
        }
    }

    @Test
    fun `circle and square markers render in PDF when the resolved family carries the glyph`() {
        // Regression for #401: in standard (non-PDF/A) mode the marker previously fell back to
        // iText's WinAnsi default (Helvetica), which lacks ○/■, so circle/square bullets silently
        // vanished — disagreeing with the editor, which renders them via CSS list-style-type. Both
        // render paths now set the marker font to the content family. Liberation Sans (bundled for
        // PDF/A) carries the glyphs; resolve any family ref to it to stand in for a real font.
        val liberation = javaClass.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.readBytes()
        val resolver = FontFamilyResolver { _, _, _, _ -> liberation }
        val fontFamily = mapOf<String, Any>("slug" to "liberation", "catalogKey" to "system")

        for ((style, glyph) in mapOf("circle" to "○", "square" to "■")) {
            val (doc, data) = documentWithBothLists("bullet", style, fontFamily)
            val text = PdfContentExtractor.extract(renderToPdf(doc, data, resolver))
            assertEquals(
                items.size * 2,
                countOccurrences(text, glyph),
                "bullet style '$style' must render glyph '$glyph' (text + datalist) when the family carries it",
            )
        }
    }

    /**
     * Renders a document containing a text-component list followed by a datalist over the
     * same [items], then asserts the two rendered blocks are line-for-line identical — i.e.
     * the marker glyph, marker-to-text spacing and item text match between the two paths.
     *
     * @return the full extracted PDF text, for additional per-test assertions.
     */
    private fun assertListPathsAgree(listType: String, bulletStyle: String = "disc"): String {
        val (doc, data) = documentWithBothLists(listType, bulletStyle)
        val extracted = PdfContentExtractor.extract(renderToPdf(doc, data))
        val rendered = extracted
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.startsWith("--- PAGE") }
            .toList()

        assertEquals(items.size * 2, rendered.size, "expected both lists to render ${items.size} items each")
        val fromTextComponent = rendered.subList(0, items.size)
        val fromDataList = rendered.subList(items.size, rendered.size)
        assertEquals(
            fromTextComponent,
            fromDataList,
            "text-component and datalist '$listType'/'$bulletStyle' lists must render identical markers and text",
        )
        return extracted
    }

    private fun countOccurrences(haystack: String, needle: String): Int = haystack.split(needle).size - 1
}
