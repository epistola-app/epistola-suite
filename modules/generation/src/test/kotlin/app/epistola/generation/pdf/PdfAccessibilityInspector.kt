// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.kernel.pdf.PdfDictionary
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.tagging.IStructureNode
import com.itextpdf.kernel.pdf.tagging.PdfStructElem
import java.io.ByteArrayInputStream

/**
 * Reads accessibility-relevant structure back out of a rendered PDF so tests
 * can assert WCAG / PDF-UA behavior that [PdfContentExtractor] (text only)
 * cannot see: the tagged structure tree, roles, alternate descriptions, the
 * document outline (and the page each bookmark resolves to), `/Lang`, the
 * PDF/UA XMP marker and page labels.
 *
 * Mirrors the [PdfContentExtractor] pattern: open the bytes, compute an
 * immutable snapshot, close the document.
 */
object PdfAccessibilityInspector {

    /** One entry in the document outline (bookmarks). */
    data class OutlineEntry(
        /** Outline title. */
        val title: String,
        /** Nesting depth, 0 = top level. */
        val depth: Int,
        /** 1-based page the bookmark navigates to, or -1 if it could not be resolved. */
        val page: Int,
    )

    /** A structure-tree element we care about. */
    data class StructElem(val role: String, val alt: String?)

    data class Snapshot(
        val tagged: Boolean,
        val lang: String?,
        val displayDocTitle: Boolean,
        val pageLabels: List<String>,
        val xmp: String,
        /** Depth-first pre-order list of structure-element roles. */
        val structRoles: List<String>,
        /** Every structure element (role + optional /Alt). */
        val structElems: List<StructElem>,
        val outline: List<OutlineEntry>,
    ) {
        fun roleCount(role: String): Int = structRoles.count { it == role }

        /** All non-blank /Alt descriptions found anywhere in the structure tree. */
        fun alts(): List<String> = structElems.mapNotNull { it.alt?.takeIf(String::isNotBlank) }
    }

    fun inspect(pdfBytes: ByteArray): Snapshot {
        val document = PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes)))
        try {
            val catalogDict = document.catalog.pdfObject

            val lang = catalogDict.getAsString(PdfName.Lang)?.toUnicodeString()

            val displayDocTitle = catalogDict.getAsDictionary(PdfName.ViewerPreferences)
                ?.getAsBoolean(PdfName.DisplayDocTitle)
                ?.value ?: false

            val pageLabels = document.pageLabels?.toList() ?: emptyList()

            val xmp = catalogDict.getAsStream(PdfName.Metadata)
                ?.bytes
                ?.toString(Charsets.UTF_8)
                ?: ""

            val structRoles = mutableListOf<String>()
            val structElems = mutableListOf<StructElem>()
            val structTreeRoot = if (document.isTagged) document.structTreeRoot else null
            structTreeRoot?.kids?.forEach { collectStruct(it, structRoles, structElems) }

            val outline = mutableListOf<OutlineEntry>()
            val rootOutline = document.getOutlines(false)
            rootOutline?.allChildren?.forEach { collectOutline(it, 0, document, outline) }

            return Snapshot(
                tagged = document.isTagged,
                lang = lang,
                displayDocTitle = displayDocTitle,
                pageLabels = pageLabels,
                xmp = xmp,
                structRoles = structRoles,
                structElems = structElems,
                outline = outline,
            )
        } finally {
            document.close()
        }
    }

    private fun collectStruct(
        node: IStructureNode,
        roles: MutableList<String>,
        elems: MutableList<StructElem>,
    ) {
        if (node is PdfStructElem) {
            val role = node.role?.value
            if (role != null) {
                roles.add(role)
                elems.add(StructElem(role, node.alt?.toUnicodeString()))
            }
        }
        node.kids?.forEach { kid -> if (kid != null) collectStruct(kid, roles, elems) }
    }

    private fun collectOutline(
        outline: com.itextpdf.kernel.pdf.PdfOutline,
        depth: Int,
        document: PdfDocument,
        out: MutableList<OutlineEntry>,
    ) {
        out.add(OutlineEntry(outline.title, depth, resolvePage(outline, document)))
        outline.allChildren.forEach { collectOutline(it, depth + 1, document, out) }
    }

    private fun resolvePage(outline: com.itextpdf.kernel.pdf.PdfOutline, document: PdfDocument): Int = try {
        val dest = outline.destination ?: return -1
        val names = document.catalog.getNameTree(PdfName.Dests)
        when (val pageObj = dest.getDestinationPage(names)) {
            is PdfDictionary -> document.getPageNumber(pageObj)
            else -> -1
        }
    } catch (_: Exception) {
        -1
    }
}
