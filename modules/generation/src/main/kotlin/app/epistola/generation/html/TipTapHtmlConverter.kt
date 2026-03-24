package app.epistola.generation.html

import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.generation.expression.ExpressionEvaluator
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.template.model.ExpressionLanguage

/**
 * Converts TipTap JSON content to HTML strings.
 * Parallel to the PDF TipTapConverter.
 */
class TipTapHtmlConverter(
    private val expressionEvaluator: CompositeExpressionEvaluator,
    private val defaultLanguage: ExpressionLanguage = ExpressionLanguage.jsonata,
    private val renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
) {

    fun convert(
        content: Map<String, Any>?,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?> = emptyMap(),
    ): String {
        if (content == null) return ""

        @Suppress("UNCHECKED_CAST")
        val nodes = content["content"] as? List<Map<String, Any>> ?: return ""

        return nodes.mapNotNull { node -> convertNode(node, data, loopContext) }.joinToString("")
    }

    private fun convertNode(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): String? {
        val type = node["type"] as? String ?: return null

        return when (type) {
            "paragraph" -> convertParagraph(node, data, loopContext)
            "heading" -> convertHeading(node, data, loopContext)
            "bulletList" -> convertBulletList(node, data, loopContext)
            "orderedList" -> convertOrderedList(node, data, loopContext)
            else -> null
        }
    }

    private fun convertParagraph(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): String {
        val margin = renderingDefaults.paragraphMarginBottom
        val inline = convertInlineContent(node, data, loopContext)
        return """<p style="margin-top: 0; margin-bottom: ${margin}pt">$inline</p>"""
    }

    private fun convertHeading(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): String {
        @Suppress("UNCHECKED_CAST")
        val attrs = node["attrs"] as? Map<String, Any>
        val level = (attrs?.get("level") as? Number)?.toInt()?.coerceIn(1, 6) ?: 1

        val fontSize = renderingDefaults.headingFontSize(level)
        val margin = renderingDefaults.headingMargin(level)
        val inline = convertInlineContent(node, data, loopContext)
        return """<h$level style="font-size: ${fontSize}pt; margin-top: ${margin}pt; margin-bottom: ${margin}pt">$inline</h$level>"""
    }

    private fun convertBulletList(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): String {
        val marginBottom = renderingDefaults.listMarginBottom
        val marginLeft = renderingDefaults.listMarginLeft
        val items = convertListItems(node, data, loopContext)
        return """<ul style="margin-bottom: ${marginBottom}pt; margin-left: ${marginLeft}pt">$items</ul>"""
    }

    private fun convertOrderedList(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): String {
        val marginBottom = renderingDefaults.listMarginBottom
        val marginLeft = renderingDefaults.listMarginLeft
        val items = convertListItems(node, data, loopContext)
        return """<ol style="margin-bottom: ${marginBottom}pt; margin-left: ${marginLeft}pt">$items</ol>"""
    }

    private fun convertListItems(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): String {
        @Suppress("UNCHECKED_CAST")
        val items = node["content"] as? List<Map<String, Any>> ?: return ""
        val itemMargin = renderingDefaults.listItemMarginBottom

        return items.filter { it["type"] == "listItem" }.joinToString("") { item ->
            @Suppress("UNCHECKED_CAST")
            val content = item["content"] as? List<Map<String, Any>> ?: emptyList()
            val inner = content.filter { it["type"] == "paragraph" }.joinToString("") { para ->
                val inline = convertInlineContent(para, data, loopContext)
                "<p style=\"margin: 0\">$inline</p>"
            }
            """<li style="margin-bottom: ${itemMargin}pt">$inner</li>"""
        }
    }

    private fun convertInlineContent(
        node: Map<String, Any>,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): String {
        @Suppress("UNCHECKED_CAST")
        val content = node["content"] as? List<Map<String, Any>> ?: return ""

        return buildString {
            for (child in content) {
                val type = child["type"] as? String ?: continue
                when (type) {
                    "text" -> {
                        val textContent = child["text"] as? String ?: ""
                        val processedText = expressionEvaluator.processTemplate(
                            textContent,
                            defaultLanguage,
                            data,
                            loopContext,
                        )
                        val escaped = HtmlEscaper.escape(processedText)

                        @Suppress("UNCHECKED_CAST")
                        val marks = child["marks"] as? List<Map<String, Any>>
                        append(applyMarks(escaped, marks))
                    }
                    "hard_break", "hardBreak" -> append("<br>")
                    "expression" -> {
                        @Suppress("UNCHECKED_CAST")
                        val attrs = child["attrs"] as? Map<String, Any>
                        val expressionRaw = attrs?.get("expression") as? String ?: ""
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
                        append(HtmlEscaper.escape(ExpressionEvaluator.valueToString(value)))
                    }
                }
            }
        }
    }

    private fun applyMarks(text: String, marks: List<Map<String, Any>>?): String {
        if (marks.isNullOrEmpty()) return text

        var result = text

        // Check for link mark
        val linkMark = marks.firstOrNull { it["type"] == "link" }
        if (linkMark != null) {
            @Suppress("UNCHECKED_CAST")
            val attrs = linkMark["attrs"] as? Map<String, Any>
            val href = attrs?.get("href") as? String
            if (!href.isNullOrBlank()) {
                result = """<a href="${HtmlEscaper.escape(href)}" style="color: #0000EE; text-decoration: underline">$result</a>"""
            }
        }

        for (mark in marks) {
            when (mark["type"]) {
                "bold" -> result = "<strong>$result</strong>"
                "italic" -> result = "<em>$result</em>"
                "underline" -> result = "<u>$result</u>"
                "strike" -> result = "<s>$result</s>"
                "textStyle" -> {
                    @Suppress("UNCHECKED_CAST")
                    val attrs = mark["attrs"] as? Map<String, Any>
                    val color = attrs?.get("color") as? String
                    if (color != null && color.startsWith("#")) {
                        result = """<span style="color: ${HtmlEscaper.escape(color)}">$result</span>"""
                    }
                }
                "link" -> { /* already handled above */ }
            }
        }

        return result
    }
}
