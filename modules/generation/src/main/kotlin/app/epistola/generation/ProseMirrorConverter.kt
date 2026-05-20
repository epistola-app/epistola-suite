package app.epistola.generation

import app.epistola.catalog.protocol.FontRef
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.generation.pdf.BookmarkEntry
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.generation.pdf.StyleApplicator
import app.epistola.template.model.ExpressionLanguage
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.pdf.action.PdfAction
import com.itextpdf.kernel.pdf.tagging.StandardRoles
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.Link
import com.itextpdf.layout.element.List
import com.itextpdf.layout.element.ListItem
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Text
import com.itextpdf.layout.properties.ListNumberingType

/**
 * Converts ProseMirror JSON content to iText PDF elements.
 *
 * ProseMirror JSON structure:
 * {
 *   "type": "doc",
 *   "content": [
 *     { "type": "paragraph", "content": [...] },
 *     { "type": "heading", "attrs": { "level": 1 }, "content": [...] },
 *     ...
 *   ]
 * }
 *
 * Inline content structure:
 * { "type": "text", "text": "Hello", "marks": [{ "type": "bold" }] }
 * { "type": "expression", "attrs": { "expression": "customer.name" } }
 */
class ProseMirrorConverter(
    private val expressionEvaluator: CompositeExpressionEvaluator,
    private val defaultLanguage: ExpressionLanguage = ExpressionLanguage.jsonata,
    private val renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
) {
    /**
     * Converts ProseMirror JSON content to a list of iText block elements.
     */
    /**
     * @param resolvedStyles Resolved style cascade for the parent text node.
     *   ProseMirrorConverter reads properties like `lineHeight` from this map
     *   and applies them to generated paragraphs/headings.
     */
    fun convert(
        content: Map<String, Any>?,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
        fontCache: app.epistola.generation.pdf.FontCache,
        resolvedStyles: Map<String, Any> = emptyMap(),
        bookmarkCollector: MutableList<BookmarkEntry>? = null,
    ): kotlin.collections.List<IBlockElement> {
        if (content == null) return emptyList()

        @Suppress("UNCHECKED_CAST")
        val nodes = content["content"] as? kotlin.collections.List<Map<String, Any>> ?: return emptyList()

        return nodes.flatMap { node -> convertNode(node, data, loopContext, fontCache, resolvedStyles, bookmarkCollector) }
    }

    private fun convertNode(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        resolvedStyles: Map<String, Any>,
        bookmarkCollector: MutableList<BookmarkEntry>?,
    ): kotlin.collections.List<IBlockElement> {
        val type = node["type"] as? String ?: return emptyList()

        val face = FaceContext.from(resolvedStyles)
        return when (type) {
            "paragraph" -> convertParagraph(node, data, loopContext, fontCache, resolvedStyles, face)
            "heading" -> convertHeading(node, data, loopContext, fontCache, resolvedStyles, bookmarkCollector, face)
            "bulletList", "bullet_list" -> listOf(convertBulletList(node, data, loopContext, fontCache, face))
            "orderedList", "ordered_list" -> listOf(convertOrderedList(node, data, loopContext, fontCache, face))
            else -> emptyList()
        }
    }

    /**
     * The font face the surrounding text node resolves to: the selected
     * family ([ref]) plus the base CSS [weight]/[italic] from the resolved
     * style cascade. Rich-text marks and headings derive their effective
     * face from this so bold/italic and headings render in the *selected*
     * family (via [app.epistola.generation.pdf.FontCache.font]) instead of
     * the built-in Liberation/Helvetica fallback.
     */
    private data class FaceContext(val ref: FontRef?, val baseWeight: Int, val baseItalic: Boolean) {
        companion object {
            fun from(resolvedStyles: Map<String, Any>): FaceContext = FaceContext(
                ref = StyleApplicator.parseFontRef(resolvedStyles["fontFamily"]),
                baseWeight = StyleApplicator.parseFontWeight(resolvedStyles["fontWeight"]),
                baseItalic = resolvedStyles["fontStyle"] == "italic",
            )
        }
    }

    /**
     * Converts a paragraph node, splitting at hard breaks into separate Paragraph elements.
     * Only the last paragraph gets marginBottom spacing.
     */
    private fun convertParagraph(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        resolvedStyles: Map<String, Any>,
        face: FaceContext,
    ): kotlin.collections.List<Paragraph> {
        @Suppress("UNCHECKED_CAST")
        val content = node["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

        // Split content at hard breaks into segments
        val segments = mutableListOf<kotlin.collections.List<Map<String, Any>>>()
        var current = mutableListOf<Map<String, Any>>()
        for (child in content) {
            val childType = child["type"] as? String
            if (childType == "hard_break" || childType == "hardBreak") {
                segments.add(current)
                current = mutableListOf()
            } else {
                current.add(child)
            }
        }
        segments.add(current)

        return segments.mapIndexed { index, segment ->
            val paragraph = Paragraph()
            applyTextStyles(paragraph, resolvedStyles)
            // Hard break lines: no spacing between them, only the last gets paragraph margin
            paragraph.setMarginTop(0f)
            paragraph.setMarginBottom(if (index == segments.size - 1) renderingDefaults.paragraphMarginBottom else 0f)
            paragraph.setPaddingTop(0f)
            paragraph.setPaddingBottom(0f)
            paragraph.setSpacingRatio(0f)
            if (segment.isNotEmpty()) {
                addInlineContent(paragraph, segment, data, loopContext, fontCache, face)
            }
            paragraph
        }
    }

    private fun convertHeading(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        resolvedStyles: Map<String, Any>,
        bookmarkCollector: MutableList<BookmarkEntry>?,
        face: FaceContext,
    ): kotlin.collections.List<Paragraph> {
        @Suppress("UNCHECKED_CAST")
        val attrs = node["attrs"] as? Map<String, Any>
        val level = (attrs?.get("level") as? Number)?.toInt() ?: 1
        val fontSize = renderingDefaults.headingFontSize(level)
        val marginVertical = renderingDefaults.headingMargin(level)
        val headingRole = getHeadingRole(level)

        @Suppress("UNCHECKED_CAST")
        val content = node["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

        // Collect a single outline/bookmark entry for the whole heading (WCAG PDF2).
        // The destination is anchored on the first segment so the bookmark
        // navigates to the heading's actual page.
        val headingText = if (bookmarkCollector != null) extractPlainText(content, data, loopContext) else ""
        val destinationName = if (bookmarkCollector != null && headingText.isNotBlank()) {
            "h_${bookmarkCollector.size}"
        } else {
            null
        }

        // Split at hard breaks
        val segments = mutableListOf<kotlin.collections.List<Map<String, Any>>>()
        var current = mutableListOf<Map<String, Any>>()
        for (child in content) {
            val childType = child["type"] as? String
            if (childType == "hard_break" || childType == "hardBreak") {
                segments.add(current)
                current = mutableListOf()
            } else {
                current.add(child)
            }
        }
        segments.add(current)

        val paragraphs = segments.mapIndexed { index, segment ->
            val paragraph = Paragraph()
            // Headings are bold by default, but in the *selected* family.
            paragraph.setFont(
                fontCache.font(
                    face.ref,
                    maxOf(face.baseWeight, app.epistola.generation.pdf.FontCache.BOLD_THRESHOLD),
                    face.baseItalic,
                ),
            )
            paragraph.setFontSize(fontSize)
            applyTextStyles(paragraph, resolvedStyles)
            // Hard break lines: tight spacing, only first/last get heading margins
            paragraph.setMarginTop(if (index == 0) marginVertical else 0f)
            paragraph.setMarginBottom(if (index == segments.size - 1) marginVertical else 0f)
            paragraph.setPaddingTop(0f)
            paragraph.setPaddingBottom(0f)
            paragraph.setSpacingRatio(0f)
            // Tag as a semantic heading for screen readers (WCAG PDF9)
            paragraph.accessibilityProperties.role = headingRole
            // Anchor the bookmark destination at the heading start (WCAG PDF2)
            if (index == 0 && destinationName != null) {
                paragraph.setDestination(destinationName)
            }
            if (segment.isNotEmpty()) {
                addInlineContent(paragraph, segment, data, loopContext, fontCache, face)
            }
            paragraph
        }

        if (destinationName != null) {
            bookmarkCollector!!.add(BookmarkEntry(level, headingText, destinationName))
        }

        return paragraphs
    }

    private fun getHeadingRole(level: Int): String = when (level) {
        1 -> StandardRoles.H1
        2 -> StandardRoles.H2
        3 -> StandardRoles.H3
        4 -> StandardRoles.H4
        5 -> StandardRoles.H5
        6 -> StandardRoles.H6
        else -> StandardRoles.H1
    }

    /**
     * Flattens a heading's inline content to plain text for the document
     * outline, resolving embedded expressions the same way [addInlineContent]
     * does so the bookmark label matches the rendered heading.
     */
    private fun extractPlainText(
        content: kotlin.collections.List<Map<String, Any>>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): String = buildString {
        for (child in content) {
            when (child["type"] as? String) {
                "text" -> {
                    val textContent = child["text"] as? String ?: ""
                    append(expressionEvaluator.processTemplate(textContent, defaultLanguage, data, loopContext))
                }
                "expression" -> {
                    @Suppress("UNCHECKED_CAST")
                    val exprAttrs = child["attrs"] as? Map<String, Any>
                    val expressionRaw = exprAttrs?.get("expression") as? String ?: ""
                    val language = when (exprAttrs?.get("language") as? String) {
                        "javascript" -> ExpressionLanguage.javascript
                        else -> defaultLanguage
                    }
                    val value = expressionEvaluator.evaluate(expressionRaw, language, data, loopContext)
                    append(app.epistola.generation.expression.ExpressionEvaluator.valueToString(value))
                }
            }
        }
    }.trim()

    private fun convertBulletList(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        face: FaceContext,
    ): List {
        @Suppress("UNCHECKED_CAST")
        val attrs = node["attrs"] as? Map<String, Any>
        val listStyle = attrs?.get("listStyle") as? String ?: "disc"

        val list = List()
        list.setListSymbol(renderingDefaults.bulletMarker(listStyle))
        list.setMarginBottom(renderingDefaults.listMarginBottom)
        list.setMarginLeft(renderingDefaults.listMarginLeft)

        @Suppress("UNCHECKED_CAST")
        val items = node["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

        for (item in items) {
            if (item["type"] == "listItem" || item["type"] == "list_item") {
                val listItem = convertListItem(item, data, loopContext, fontCache, face)
                list.add(listItem)
            }
        }

        return list
    }

    private fun convertOrderedList(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        face: FaceContext,
    ): List {
        @Suppress("UNCHECKED_CAST")
        val attrs = node["attrs"] as? Map<String, Any>
        val listTypeStr = attrs?.get("listType") as? String ?: "decimal"
        val startNumber = (attrs?.get("order") as? Number)?.toInt() ?: 1

        val numberingType = when (listTypeStr) {
            "lower-alpha" -> ListNumberingType.ENGLISH_LOWER
            "upper-alpha" -> ListNumberingType.ENGLISH_UPPER
            "lower-roman" -> ListNumberingType.ROMAN_LOWER
            "upper-roman" -> ListNumberingType.ROMAN_UPPER
            else -> ListNumberingType.DECIMAL
        }

        val list = List(numberingType)
        if (startNumber > 1) list.setItemStartIndex(startNumber)
        list.setMarginBottom(renderingDefaults.listMarginBottom)
        list.setMarginLeft(renderingDefaults.listMarginLeft)

        @Suppress("UNCHECKED_CAST")
        val items = node["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

        for (item in items) {
            if (item["type"] == "listItem" || item["type"] == "list_item") {
                val listItem = convertListItem(item, data, loopContext, fontCache, face)
                list.add(listItem)
            }
        }

        return list
    }

    private fun convertListItem(
        item: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        face: FaceContext,
    ): ListItem {
        val listItem = ListItem()
        listItem.setMarginBottom(renderingDefaults.listItemMarginBottom)

        @Suppress("UNCHECKED_CAST")
        val content = item["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

        // List items typically contain paragraphs
        for (child in content) {
            if (child["type"] == "paragraph") {
                @Suppress("UNCHECKED_CAST")
                val paragraphContent = child["content"] as? kotlin.collections.List<Map<String, Any>>
                if (paragraphContent != null) {
                    val paragraph = Paragraph()
                    addInlineContent(paragraph, paragraphContent, data, loopContext, fontCache, face)
                    listItem.add(paragraph)
                }
            }
        }

        return listItem
    }

    private fun addInlineContent(
        paragraph: Paragraph,
        content: kotlin.collections.List<Map<String, Any>>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        face: FaceContext,
    ) {
        for (child in content) {
            val type = child["type"] as? String ?: continue

            when (type) {
                "text" -> {
                    val textContent = child["text"] as? String ?: ""
                    // Process any embedded expressions in the text using the default language
                    val processedText = expressionEvaluator.processTemplate(
                        textContent,
                        defaultLanguage,
                        data,
                        loopContext,
                    )

                    // Check for link mark to create a Link element instead of plain Text
                    @Suppress("UNCHECKED_CAST")
                    val marks = child["marks"] as? kotlin.collections.List<Map<String, Any>>
                    val linkHref = marks?.findLinkHref()

                    val text: Text = if (linkHref != null) {
                        val link = Link(processedText, PdfAction.createURI(linkHref))
                            .setUnderline()
                            .setFontColor(LINK_COLOR)
                        // Accessible description for the link (WCAG PDF13)
                        link.accessibilityProperties.setAlternateDescription(processedText)
                        link
                    } else {
                        Text(processedText)
                    }

                    // Apply marks (formatting)
                    if (marks != null) {
                        applyMarks(text, marks, fontCache, face)
                    }

                    paragraph.add(text)
                }
                "hard_break", "hardBreak" -> {
                    // Hard breaks are handled by splitting paragraphs in convertParagraph/convertHeading.
                    // This case should not be reached, but is kept as a safe fallback.
                }
                "expression" -> {
                    // Expression atom node
                    @Suppress("UNCHECKED_CAST")
                    val attrs = child["attrs"] as? Map<String, Any>
                    val expressionRaw = attrs?.get("expression") as? String ?: ""
                    // Get language from attrs, default to the converter's default
                    val languageStr = attrs?.get("language") as? String
                    val language = when (languageStr) {
                        "javascript" -> ExpressionLanguage.javascript
                        else -> defaultLanguage
                    }
                    val value = expressionEvaluator.evaluate(
                        expressionRaw,
                        language,
                        data,
                        loopContext,
                    )
                    if (isRichTextDoc(value)) {
                        addRichTextInline(paragraph, value, fontCache, face)
                    } else {
                        paragraph.add(Text(app.epistola.generation.expression.ExpressionEvaluator.valueToString(value)))
                    }
                }
            }
        }
    }

    /**
     * Returns true when the resolved value matches the rich-text doc shape
     * (`{ "type": "doc", "content": [...] }`) so we can inline its content
     * instead of falling back to `valueToString`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun isRichTextDoc(value: Any?): Boolean {
        val map = value as? Map<String, Any?> ?: return false
        if (map["type"] != "doc") return false
        return map["content"] is kotlin.collections.List<*>
    }

    /**
     * Inline the inline content of a rich-text doc into the surrounding paragraph.
     *
     * Phase 1: only paragraph-level inline content (text + marks, hard breaks)
     * is rendered; block-level content (lists, multiple paragraphs) is dropped
     * here and a debug log records the skipped node types — block content
     * belongs in the `richTextVariable` component, not in an inline expression
     * chip. The `expressionEvaluator.processTemplate` call is intentionally
     * skipped because phase-1 rich-text values may not contain expressions.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addRichTextInline(
        paragraph: Paragraph,
        doc: Any?,
        fontCache: app.epistola.generation.pdf.FontCache,
        face: FaceContext,
    ) {
        val docMap = doc as? Map<String, Any?> ?: return
        val blocks = docMap["content"] as? kotlin.collections.List<Map<String, Any>> ?: return
        for (block in blocks) {
            val blockType = block["type"] as? String
            if (blockType != "paragraph") {
                LOGGER.debug("Dropping block-level rich-text node '{}' from inline context", blockType)
                continue
            }
            val inline = block["content"] as? kotlin.collections.List<Map<String, Any>> ?: continue
            for (child in inline) {
                when (child["type"] as? String) {
                    "text" -> {
                        val textContent = child["text"] as? String ?: ""
                        val marks = child["marks"] as? kotlin.collections.List<Map<String, Any>>
                        val linkHref = marks?.findLinkHref()
                        val text: Text = if (linkHref != null) {
                            val link = Link(textContent, PdfAction.createURI(linkHref))
                                .setUnderline()
                                .setFontColor(LINK_COLOR)
                            // Accessible description for the link (WCAG PDF13)
                            link.accessibilityProperties.setAlternateDescription(textContent)
                            link
                        } else {
                            Text(textContent)
                        }
                        if (marks != null) applyMarks(text, marks, fontCache, face)
                        paragraph.add(text)
                    }
                    "hard_break", "hardBreak" -> {
                        // Soft fall-through inside an inline binding: a literal newline
                        // would split the host paragraph, which we cannot do here.
                        paragraph.add(Text(" "))
                    }
                }
            }
        }
    }

    private fun applyMarks(text: Text, marks: kotlin.collections.List<Map<String, Any>>, fontCache: app.epistola.generation.pdf.FontCache, face: FaceContext) {
        var isBold = false
        var isItalic = false

        for (mark in marks) {
            when (mark["type"]) {
                "bold", "strong" -> isBold = true
                "italic", "em" -> isItalic = true
                "underline" -> text.setUnderline()
                "strike", "strikethrough" -> text.setLineThrough()
                "subscript" -> {
                    text.setTextRise(-3f)
                    text.setFontSize(renderingDefaults.baseFontSizePt * 0.75f)
                }
                "superscript" -> {
                    text.setTextRise(5f)
                    text.setFontSize(renderingDefaults.baseFontSizePt * 0.75f)
                }
                "link" -> { /* handled separately via Link element */ }
                "textStyle" -> {
                    @Suppress("UNCHECKED_CAST")
                    val attrs = mark["attrs"] as? Map<String, Any>
                    attrs?.get("color")?.let { color ->
                        if (color is String && color.startsWith("#")) {
                            val deviceRgb = parseHexColor(color)
                            if (deviceRgb != null) {
                                text.setFontColor(deviceRgb)
                            }
                        }
                    }
                }
            }
        }

        // A bold/italic mark changes the face relative to the surrounding
        // text's base. Resolve through the *selected* family (via FontCache;
        // a null family falls back to the built-in by weight/italic). When no
        // weight/italic-affecting mark is present, leave the font unset so the
        // run inherits the container's already-resolved family face.
        if (isBold || isItalic) {
            val effectiveWeight = if (isBold) {
                maxOf(face.baseWeight, app.epistola.generation.pdf.FontCache.BOLD_THRESHOLD)
            } else {
                face.baseWeight
            }
            val effectiveItalic = face.baseItalic || isItalic
            text.setFont(fontCache.font(face.ref, effectiveWeight, effectiveItalic))
        }
    }

    companion object {
        private val LOGGER = org.slf4j.LoggerFactory.getLogger(ProseMirrorConverter::class.java)

        /** Standard link color (blue) used for hyperlinks in PDF output. */
        private val LINK_COLOR = DeviceRgb(0, 0, 238)

        /** Extracts the href from a link mark, if present. */
        private fun kotlin.collections.List<Map<String, Any>>.findLinkHref(): String? {
            val linkMark = firstOrNull { it["type"] == "link" } ?: return null

            @Suppress("UNCHECKED_CAST")
            val attrs = linkMark["attrs"] as? Map<String, Any> ?: return null
            val href = attrs["href"] as? String
            return href?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Apply resolved text styles (lineHeight, etc.) to a paragraph.
     * This is the single place to add new style properties that affect ProseMirror paragraphs/headings.
     */
    /**
     * Apply pre-resolved text styles to a paragraph.
     * Values in the map should already be in points (resolved by the caller).
     */
    private fun applyTextStyles(paragraph: Paragraph, resolvedStyles: Map<String, Any>) {
        (resolvedStyles["lineHeight"] as? Float)?.let {
            paragraph.setMultipliedLeading(it)
        }
    }

    private fun parseHexColor(hex: String): DeviceRgb? = try {
        val cleanHex = hex.removePrefix("#")
        when (cleanHex.length) {
            6 -> {
                val r = cleanHex.substring(0, 2).toInt(16)
                val g = cleanHex.substring(2, 4).toInt(16)
                val b = cleanHex.substring(4, 6).toInt(16)
                DeviceRgb(r, g, b)
            }
            3 -> {
                val r = cleanHex.substring(0, 1).repeat(2).toInt(16)
                val g = cleanHex.substring(1, 2).repeat(2).toInt(16)
                val b = cleanHex.substring(2, 3).repeat(2).toInt(16)
                DeviceRgb(r, g, b)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
