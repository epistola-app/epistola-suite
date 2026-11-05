// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import com.itextpdf.kernel.pdf.tagging.IStructureNode
import com.itextpdf.kernel.pdf.tagging.PdfMcr
import com.itextpdf.kernel.pdf.tagging.PdfStructElem
import java.io.ByteArrayInputStream

/**
 * Reads back *how* text was drawn — its marked-content tag hierarchy — rather than
 * what it says.
 *
 * PDF/UA-1 (ISO 14289-1) requires every piece of content in a tagged document to be
 * either **structure content** (inside a marked-content sequence carrying an MCID,
 * which links it into the structure tree) or an explicit **artifact** (decorative,
 * skipped by screen readers). Content that is neither is a conformance violation.
 *
 * [PdfAccessibilityInspector] cannot see this: it reads the structure tree, and
 * unmarked content is absent from the structure tree in exactly the same way a
 * correctly marked artifact is. Distinguishing the two needs the content stream.
 */
object PdfMarkedContentInspector {

    /** One show-text operation and the marked-content tags enclosing it. */
    data class TextChunk(
        val text: String,
        val tags: List<String>,
        val hasMcid: Boolean,
    ) {
        val isArtifact: Boolean get() = tags.contains(PdfName.Artifact.value)

        /** Neither structure content nor an artifact — illegal in a tagged/PDF-UA document. */
        val isUnmarked: Boolean get() = !isArtifact && !hasMcid
    }

    fun chunks(pdfBytes: ByteArray): List<TextChunk> {
        val collected = mutableListOf<TextChunk>()
        val listener = object : IEventListener {
            override fun eventOccurred(data: IEventData, type: EventType) {
                val info = data as? TextRenderInfo ?: return
                if (info.text.isBlank()) return
                val hierarchy = info.canvasTagHierarchy
                collected += TextChunk(
                    text = info.text,
                    tags = hierarchy.map { it.role.value },
                    hasMcid = hierarchy.any { it.hasMcid() },
                )
            }

            override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
        }

        PdfReader(ByteArrayInputStream(pdfBytes)).use { reader ->
            PdfDocument(reader).use { document ->
                for (page in 1..document.numberOfPages) {
                    PdfCanvasProcessor(listener).processPageContent(document.getPage(page))
                }
            }
        }
        return collected
    }

    /** Text drawn as neither structure content nor an artifact — should always be empty. */
    fun unmarkedText(pdfBytes: ByteArray): List<String> = chunks(pdfBytes).filter { it.isUnmarked }.map { it.text }

    /** Text drawn inside an `/Artifact` marked-content sequence. */
    fun artifactText(pdfBytes: ByteArray): List<String> = chunks(pdfBytes).filter { it.isArtifact }.map { it.text }

    /**
     * Structure content in the order a screen reader announces it: the structure tree walked
     * depth first, each marked-content reference replaced by the text drawn under its MCID.
     * Paint order on the page does not matter; where a tag sits in the tree does.
     */
    fun readingOrderText(pdfBytes: ByteArray): List<String> {
        PdfReader(ByteArrayInputStream(pdfBytes)).use { reader ->
            PdfDocument(reader).use { document ->
                val textByMcid = HashMap<Pair<Int, Int>, StringBuilder>()
                for (page in 1..document.numberOfPages) {
                    val listener = object : IEventListener {
                        override fun eventOccurred(data: IEventData, type: EventType) {
                            val info = data as? TextRenderInfo ?: return
                            if (info.mcid >= 0) textByMcid.getOrPut(page to info.mcid) { StringBuilder() }.append(info.text)
                        }

                        override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
                    }
                    PdfCanvasProcessor(listener).processPageContent(document.getPage(page))
                }

                val ordered = mutableListOf<String>()
                fun walk(node: IStructureNode) {
                    when (node) {
                        is PdfMcr -> textByMcid[document.getPageNumber(node.pageObject) to node.mcid]
                            ?.toString()?.takeIf { it.isNotBlank() }?.let(ordered::add)

                        is PdfStructElem -> node.kids?.forEach { kid -> if (kid != null) walk(kid) }
                    }
                }
                document.structTreeRoot?.kids?.forEach { kid -> if (kid != null) walk(kid) }
                return ordered
            }
        }
    }
}
