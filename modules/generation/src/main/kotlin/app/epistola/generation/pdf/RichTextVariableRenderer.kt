// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement
import org.slf4j.LoggerFactory

/**
 * Renders a `richTextVariable` node — resolves its `binding` against the data
 * via [CompositeExpressionEvaluator][app.epistola.generation.expression.CompositeExpressionEvaluator],
 * expects a ProseMirror rich-text doc, and feeds it through [ProseMirrorConverter]
 * to emit block-level iText elements (paragraphs, lists, marks).
 *
 * Props:
 *   - `binding` — expression resolving to a rich-text doc (`{ type: 'doc', content: [...] }`)
 *   - `language` — optional, defaults to the context's default expression language
 *
 * If the binding resolves to a non-doc value (null, plain string, primitive),
 * the renderer emits an empty Div and logs at debug. This is intentionally
 * forgiving: previewing a template with missing example data should not abort
 * generation.
 */
class RichTextVariableRenderer : NodeRenderer {

    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val div = Div()

        StyleApplicator.applyStylesWithPreset(
            div,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.inheritedStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("richTextVariable")
                ?: context.renderingDefaults.componentDefaults("text"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )

        val binding = (node.props?.get("binding") as? String)?.trim().orEmpty()
        if (binding.isEmpty()) {
            LOGGER.debug("richTextVariable node {} has no binding", node.id)
            return listOf(div)
        }

        val language = when (node.props?.get("language") as? String) {
            "javascript" -> ExpressionLanguage.javascript
            else -> context.defaultExpressionLanguage
        }

        val value = context.expressionEvaluator.evaluate(
            binding,
            language,
            context.effectiveData,
            context.loopContext,
        )

        if (!isRichTextDoc(value)) {
            LOGGER.debug(
                "richTextVariable binding '{}' did not resolve to a rich-text doc (got {})",
                binding,
                value?.javaClass?.simpleName,
            )
            return listOf(div)
        }

        @Suppress("UNCHECKED_CAST")
        val doc = value as Map<String, Any>

        val rawStyles = buildMap<String, Any> {
            putAll(context.inheritedStyles)
            StyleApplicator.resolveBlockStyles(
                context.blockStylePresets,
                node.stylePreset,
                node.styles?.filterNonNullValues(),
            )?.let { putAll(it) }
        }
        val resolvedStyles = buildMap<String, Any> {
            rawStyles["lineHeight"]?.toString()?.toFloatOrNull()?.let {
                put("lineHeight", it)
            }
            // Forward the resolved font face so bound rich-text headings and
            // bold/italic marks render in the selected family (see TextNodeRenderer).
            rawStyles["fontFamily"]?.let { put("fontFamily", it) }
            rawStyles["fontWeight"]?.let { put("fontWeight", it) }
            rawStyles["fontStyle"]?.let { put("fontStyle", it) }
        }

        val elements = context.proseMirrorConverter.convert(
            doc,
            context.effectiveData,
            context.loopContext,
            context.fontCache,
            resolvedStyles,
        )

        for (element in elements) {
            div.add(element)
        }

        return listOf(div)
    }

    private fun isRichTextDoc(value: Any?): Boolean {
        @Suppress("UNCHECKED_CAST")
        val map = value as? Map<String, Any?> ?: return false
        if (map["type"] != "doc") return false
        return map["content"] is List<*>
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(RichTextVariableRenderer::class.java)
    }
}
