package app.epistola.generation

import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.ExpressionLanguage
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.layout.element.IBlockElement
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
    private val defaultLanguage: ExpressionLanguage = ExpressionLanguage.Jsonata,
) {
    /**
     * Converts TipTap JSON content to a list of iText block elements.
     */
    fun convert(
        content: Map<String, Any>?,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
        fontCache: app.epistola.generation.pdf.FontCache,
        lineHeight: String? = null,
    ): kotlin.collections.List<IBlockElement> {
        if (content == null) return emptyList()

        @Suppress("UNCHECKED_CAST")
        val nodes = content["content"] as? kotlin.collections.List<Map<String, Any>> ?: return emptyList()

        return nodes.mapNotNull { node -> convertNode(node, data, loopContext, fontCache, lineHeight) }
    }

    private fun convertNode(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        lineHeight: String?,
    ): IBlockElement? {
        val type = node["type"] as? String ?: return null

        return when (type) {
            "paragraph" -> convertParagraph(node, data, loopContext, fontCache, lineHeight)
            "heading" -> convertHeading(node, data, loopContext, fontCache, lineHeight)
            "bulletList" -> convertBulletList(node, data, loopContext, fontCache, lineHeight)
            "orderedList" -> convertOrderedList(node, data, loopContext, fontCache, lineHeight)
            else -> null
        }
    }

    private fun convertParagraph(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        lineHeight: String?,
    ): Paragraph {
        val paragraph = Paragraph()
        paragraph.setMarginBottom(12f) // ~1em
        applyLineHeight(paragraph, lineHeight)

        @Suppress("UNCHECKED_CAST")
        val content = node["content"] as? kotlin.collections.List<Map<String, Any>>
        if (content != null) {
            addInlineContent(paragraph, content, data, loopContext, fontCache)
        }

        return paragraph
    }

    private fun convertHeading(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        lineHeight: String?,
    ): Paragraph {
        @Suppress("UNCHECKED_CAST")
        val attrs = node["attrs"] as? Map<String, Any>
        val level = (attrs?.get("level") as? Number)?.toInt() ?: 1

        val paragraph = Paragraph()
        paragraph.setMarginBottom(6f) // 0.5em
        paragraph.setFont(fontCache.bold)

        // Set font size based on heading level
        val fontSize = when (level) {
            1 -> 24f // 2em
            2 -> 18f // 1.5em
            3 -> 14f // 1.17em
            else -> 12f
        }
        paragraph.setFontSize(fontSize)
        applyLineHeight(paragraph, lineHeight)

        @Suppress("UNCHECKED_CAST")
        val content = node["content"] as? kotlin.collections.List<Map<String, Any>>
        if (content != null) {
            addInlineContent(paragraph, content, data, loopContext, fontCache)
        }

        return paragraph
    }

    private fun convertBulletList(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
        fontCache: app.epistola.generation.pdf.FontCache,
        lineHeight: String?,
    ): List {
        val list = List()
        list.setMarginBottom(12f)
        list.setMarginLeft(24f)

        @Suppress("UNCHECKED_CAST")
        val items = node["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

        for (item in items) {
            if (item["type"] == "listItem") {
                val listItem = convertListItem(item, data, loopContext, fontCache, lineHeight)
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
        lineHeight: String?,
    ): List {
        val list = List(ListNumberingType.DECIMAL)
        list.setMarginBottom(12f)
        list.setMarginLeft(24f)

        @Suppress("UNCHECKED_CAST")
        val items = node["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

        for (item in items) {
            if (item["type"] == "listItem") {
                val listItem = convertListItem(item, data, loopContext, fontCache, lineHeight)
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
        lineHeight: String?,
    ): ListItem {
        val listItem = ListItem()

        @Suppress("UNCHECKED_CAST")
        val content = item["content"] as? kotlin.collections.List<Map<String, Any>> ?: emptyList()

        // List items typically contain paragraphs
        for (child in content) {
            if (child["type"] == "paragraph") {
                @Suppress("UNCHECKED_CAST")
                val paragraphContent = child["content"] as? kotlin.collections.List<Map<String, Any>>
                if (paragraphContent != null) {
                    val paragraph = Paragraph()
                    applyLineHeight(paragraph, lineHeight)
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

                    val text = Text(processedText)

                    // Apply marks (formatting)
                    @Suppress("UNCHECKED_CAST")
                    val marks = child["marks"] as? kotlin.collections.List<Map<String, Any>>
                    if (marks != null) {
                        applyMarks(text, marks, fontCache)
                    }

                    paragraph.add(text)
                }
                "expression" -> {
                    // Expression atom node
                    @Suppress("UNCHECKED_CAST")
                    val attrs = child["attrs"] as? Map<String, Any>
                    val expressionRaw = attrs?.get("expression") as? String ?: ""
                    // Get language from attrs, default to the converter's default
                    val languageStr = attrs?.get("language") as? String
                    val language = when (languageStr) {
                        "javascript" -> ExpressionLanguage.JavaScript
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

    private fun applyLineHeight(paragraph: Paragraph, lineHeight: String?) {
        val normalized = lineHeight?.trim()?.lowercase() ?: return
        if (normalized.isEmpty() || normalized == "normal") return

        if (normalized.endsWith("%")) {
            normalized.removeSuffix("%").toFloatOrNull()?.let { percent ->
                if (percent > 0f) paragraph.setMultipliedLeading(percent / 100f)
            }
            return
        }

        if (hasCssUnit(normalized)) {
            parseSizeToPoints(normalized)?.let { size ->
                if (size > 0f) paragraph.setFixedLeading(size)
            }
            return
        }

        normalized.toFloatOrNull()?.let { multiplier ->
            if (multiplier > 0f) paragraph.setMultipliedLeading(multiplier)
        }
    }

    private fun hasCssUnit(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized.endsWith("px") ||
            normalized.endsWith("pt") ||
            normalized.endsWith("mm") ||
            normalized.endsWith("cm") ||
            normalized.endsWith("rem") ||
            normalized.endsWith("em")
    }

    private fun parseSizeToPoints(size: String): Float? {
        val normalized = size.trim().lowercase()
        return when {
            normalized.endsWith("px") -> normalized.removeSuffix("px").toFloatOrNull()?.let { it * 0.75f }
            normalized.endsWith("pt") -> normalized.removeSuffix("pt").toFloatOrNull()
            normalized.endsWith("mm") -> normalized.removeSuffix("mm").toFloatOrNull()?.let { it * 2.83465f }
            normalized.endsWith("cm") -> normalized.removeSuffix("cm").toFloatOrNull()?.let { it * 28.3465f }
            normalized.endsWith("rem") -> normalized.removeSuffix("rem").toFloatOrNull()?.let { it * 12f }
            normalized.endsWith("em") -> normalized.removeSuffix("em").toFloatOrNull()?.let { it * 12f }
            else -> null
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
