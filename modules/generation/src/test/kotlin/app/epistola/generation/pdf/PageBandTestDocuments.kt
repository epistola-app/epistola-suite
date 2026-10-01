// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A small builder for template documents with page headers, footers and page breaks, shared by
 * the page-band tests. Every builder function registers its node and returns its id, so a
 * document reads in flow order: `doc.build(doc.header("h", doc.text("t", "HEADER")), …)`.
 */
internal class BandDoc {
    private val nodes = linkedMapOf<String, Node>()
    private val slots = linkedMapOf<String, Slot>()

    fun text(id: String, text: String): String = add(
        Node(id = id, type = "text", props = mapOf("content" to paragraph(listOf(mapOf("type" to "text", "text" to text))))),
    )

    /** A paragraph of [prefix] followed by the value of [expression]. */
    fun expressionText(id: String, prefix: String, expression: String): String = add(
        Node(
            id = id,
            type = "text",
            props = mapOf(
                "content" to paragraph(
                    listOf(
                        mapOf("type" to "text", "text" to prefix),
                        mapOf("type" to "expression", "attrs" to mapOf("expression" to expression)),
                    ),
                ),
            ),
        ),
    )

    /** [count] paragraphs long enough to fill several pages together. */
    fun body(prefix: String, count: Int): List<String> = (1..count).map {
        text(
            "$prefix-$it",
            "$prefix paragraph $it. This paragraph is long enough to wrap across several lines, so that a " +
                "handful of them fill a page and push the flow onto the next one for the band tests.",
        )
    }

    fun header(id: String, vararg children: String, props: Map<String, Any?> = emptyMap()): String = withChildren(id, "pageheader", "children", children.toList(), props)

    fun footer(id: String, vararg children: String, props: Map<String, Any?> = emptyMap()): String = withChildren(id, "pagefooter", "children", children.toList(), props)

    fun pageBreak(id: String): String = add(Node(id = id, type = "pagebreak"))

    fun container(id: String, vararg children: String): String = withChildren(id, "container", "children", children.toList())

    fun stencil(id: String, vararg children: String): String = withChildren(id, "stencil", "children", children.toList(), mapOf("stencilId" to "letter-shell", "version" to 1))

    fun conditional(id: String, condition: String, vararg children: String): String = withChildren(id, "conditional", "body", children.toList(), mapOf("condition" to mapOf("raw" to condition, "language" to "jsonata")))

    fun loop(id: String, expression: String, vararg children: String): String = withChildren(
        id,
        "loop",
        "body",
        children.toList(),
        mapOf("expression" to mapOf("raw" to expression, "language" to "jsonata"), "itemAlias" to "item"),
    )

    fun addressBlock(id: String): String = add(
        Node(id = id, type = "addressblock", props = mapOf("align" to "left", "top" to 45, "sideDistance" to 20, "addressWidth" to 85, "height" to 45)),
    )

    fun build(vararg rootChildren: String): TemplateDocument {
        nodes["root"] = Node(id = "root", type = "root", slots = listOf("root-slot"))
        slots["root-slot"] = Slot("root-slot", "root", "children", rootChildren.toList())
        return TemplateDocument(root = "root", nodes = nodes.toMap(), slots = slots.toMap())
    }

    private fun withChildren(id: String, type: String, slotName: String, children: List<String>, props: Map<String, Any?> = emptyMap()): String {
        slots["$id-slot"] = Slot("$id-slot", id, slotName, children)
        return add(Node(id = id, type = type, slots = listOf("$id-slot"), props = props.ifEmpty { null }))
    }

    private fun add(node: Node): String {
        nodes[node.id] = node
        return node.id
    }

    private fun paragraph(content: List<Map<String, Any?>>) = mapOf(
        "type" to "doc",
        "content" to listOf(mapOf("type" to "paragraph", "content" to content)),
    )
}

/** A text chunk as drawn: its text and baseline start, rounded to hundredths of a point. */
internal data class DrawnText(val text: String, val x: Float, val y: Float)

internal fun renderBands(
    document: TemplateDocument,
    data: Map<String, Any?> = emptyMap(),
    renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
): ByteArray = ByteArrayOutputStream().also {
    DirectPdfRenderer().render(document, data, it, renderingDefaults = renderingDefaults)
}.toByteArray()

/** Every text chunk per page (1-based), in drawing order. */
internal fun drawnText(pdfBytes: ByteArray): Map<Int, List<DrawnText>> {
    PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes))).use { pdf ->
        return (1..pdf.numberOfPages).associateWith { pageNumber ->
            val chunks = mutableListOf<DrawnText>()
            val listener = object : IEventListener {
                override fun eventOccurred(data: IEventData, type: EventType) {
                    if (type != EventType.RENDER_TEXT) return
                    val info = data as TextRenderInfo
                    val start = info.baseline.startPoint
                    fun round(value: Float) = Math.round(value * 100f) / 100f
                    chunks += DrawnText(info.text, round(start[0]), round(start[1]))
                }

                override fun getSupportedEvents() = setOf(EventType.RENDER_TEXT)
            }
            PdfCanvasProcessor(listener).processPageContent(pdf.getPage(pageNumber))
            chunks
        }
    }
}

/** The page's text joined per line, top to bottom, for readable containment assertions. */
internal fun Map<Int, List<DrawnText>>.pageText(page: Int): String = getValue(page)
    .groupBy { it.y }
    .toSortedMap(compareByDescending { it })
    .values
    .joinToString("\n") { line -> line.sortedBy { it.x }.joinToString("") { it.text } }

/** Baseline of the first line on [page] whose text starts with [prefix]. */
internal fun Map<Int, List<DrawnText>>.baselineOf(page: Int, prefix: String): Float = getValue(page)
    .groupBy { it.y }
    .entries
    .filter { (_, line) -> line.sortedBy { it.x }.joinToString("") { it.text }.startsWith(prefix) }
    .maxOf { it.key }
