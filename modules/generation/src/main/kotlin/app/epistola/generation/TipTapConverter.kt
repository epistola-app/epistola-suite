package app.epistola.generation

import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.template.model.ExpressionLanguage
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.pdf.action.PdfAction
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.Link
import com.itextpdf.layout.element.List
import com.itextpdf.layout.element.ListItem
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Text
import com.itextpdf.layout.properties.ListNumberingType

/**
 * Converts TipTap JSON content to iText PDF elements.
 *
 * TipTap JSON structure:
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
class TipTapConverter(
    private val expressionEvaluator: CompositeExpressionEvaluator,
    private val defaultLanguage: ExpressionLanguage = ExpressionLanguage.jsonata,
    private val renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
) {
    /**
     * Converts TipTap JSON content to a list of iText block elements.
     */
    /**
     * @param resolvedStyles Resolved style cascade for the parent text node.
     *   TipTapConverter reads properties like `lineHeight` from this map
     *   and applies them to generated paragraphs/headings.
     */
    fun convert(
        content: Map<String, Any>?,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
        fontCache: app.epistola.generation.pdf.FontCache,
        resolvedStyles: Map<String, Any> = emptyMap(),
    ): kotlin.collections.List<IBlockElement> {
        if (content == null) return emptyList()

        @Suppress("UNCHECKED_CAST")
        val nodes = content["content"] as? kotlin.collections.List<Map<String, Any>> ?: return emptyList()

        return nodes.flatMap { node -> convertNode(node, data, loopContext, fontCache, resolvedStyles) }
    }

    private fun convertNode(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        resolvedStyles: Map<String, Any>,
    ): kotlin.collections.List<IBlockElement> {
        val type = node["type"] as? String ?: return emptyList()

        return when (type) {
            "paragraph" -> convertParagraph(node, data, loopContext, fontCache, resolvedStyles)
            "heading" -> convertHeading(node, data, loopContext, fontCache, resolvedStyles)
            "bulletList", "bullet_list" -> listOf(convertBulletList(node, data, loopContext, fontCache))
            "orderedList", "ordered_list" -> listOf(convertOrderedList(node, data, loopContext, fontCache))
            else -> emptyList()
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
            if (index == segments.size - 1) {
                paragraph.setMarginBottom(renderingDefaults.paragraphMarginBottom)
            }
            if (segment.isNotEmpty()) {
                addInlineContent(paragraph, segment, data, loopContext, fontCache)
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
    ): kotlin.collections.List<Paragraph> {
        @Suppress("UNCHECKED_CAST")
        val attrs = node["attrs"] as? Map<String, Any>
        val level = (attrs?.get("level") as? Number)?.toInt() ?: 1
        val fontSize = renderingDefaults.headingFontSize(level)
        val marginVertical = renderingDefaults.headingMargin(level)

        @Suppress("UNCHECKED_CAST")
        val content = node["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

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

        return segments.mapIndexed { index, segment ->
            val paragraph = Paragraph()
            paragraph.setFont(fontCache.bold)
            paragraph.setFontSize(fontSize)
            applyTextStyles(paragraph, resolvedStyles)
            when (index) {
                0 -> {
                    paragraph.setMarginTop(marginVertical)
                    if (segments.size == 1) {
                        paragraph.setMarginBottom(marginVertical)
                    } else {
                        paragraph.setMarginBottom(0f)
                    }
                }
                segments.size - 1 -> paragraph.setMarginBottom(marginVertical)
                else -> paragraph.setMarginBottom(0f)
            }
            if (segment.isNotEmpty()) {
                addInlineContent(paragraph, segment, data, loopContext, fontCache)
            }
            paragraph
        }
    }

    private fun convertBulletList(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
    ): List {
        @Suppress("UNCHECKED_CAST")
        val attrs = node["attrs"] as? Map<String, Any>
        val listStyle = attrs?.get("listStyle") as? String ?: "disc"

        val list = List()
        val symbol = when (listStyle) {
            "circle" -> "\u25CB  " // ○
            "square" -> "\u25A0  " // ■
            "dash" -> "\u2013  " // –
            else -> "\u2022  " // • (disc, default)
        }
        list.setListSymbol(symbol)
        list.setMarginBottom(renderingDefaults.listMarginBottom)
        list.setMarginLeft(renderingDefaults.listMarginLeft)

        @Suppress("UNCHECKED_CAST")
        val items = node["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

        for (item in items) {
            if (item["type"] == "listItem" || item["type"] == "list_item") {
                val listItem = convertListItem(item, data, loopContext, fontCache)
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
                val listItem = convertListItem(item, data, loopContext, fontCache)
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
                    addInlineContent(paragraph, paragraphContent, data, loopContext, fontCache)
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
                        Link(processedText, PdfAction.createURI(linkHref))
                            .setUnderline()
                            .setFontColor(LINK_COLOR)
                    } else {
                        Text(processedText)
                    }

                    // Apply marks (formatting)
                    if (marks != null) {
                        applyMarks(text, marks, fontCache)
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
                    paragraph.add(Text(app.epistola.generation.expression.ExpressionEvaluator.valueToString(value)))
                }
            }
        }
    }

    private fun applyMarks(text: Text, marks: kotlin.collections.List<Map<String, Any>>, fontCache: app.epistola.generation.pdf.FontCache) {
        var isBold = false
        var isItalic = false

        for (mark in marks) {
            when (mark["type"]) {
                "bold" -> isBold = true
                "italic" -> isItalic = true
                "underline" -> text.setUnderline()
                "strike" -> text.setLineThrough()
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

        // Apply appropriate font based on bold/italic combination
        val font = when {
            isBold && isItalic -> fontCache.boldItalic
            isBold -> fontCache.bold
            isItalic -> fontCache.italic
            else -> null
        }
        font?.let { text.setFont(it) }
    }

    companion object {
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
     * This is the single place to add new style properties that affect TipTap paragraphs/headings.
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
