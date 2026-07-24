// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement

/**
 * Renders a "text" node to iText elements.
 *
 * Props:
 * - `content`: ProseMirror JSON content (Map<String, Any>)
 */
class TextNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val div = Div()

        // Apply node styles with theme preset resolution
        StyleApplicator.applyStylesWithPreset(
            div,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.inheritedStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("text"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )

        // Resolve the full style cascade, then parse size values to points.
        // ProseMirrorConverter receives pre-resolved values — it doesn't parse units.
        val rawStyles = buildMap<String, Any> {
            putAll(context.inheritedStyles)
            StyleApplicator.resolveBlockStyles(context.blockStylePresets, node.stylePreset, node.styles?.filterNonNullValues())
                ?.let { putAll(it) }
        }
        val resolvedStyles = buildMap<String, Any> {
            rawStyles["lineHeight"]?.toString()?.toFloatOrNull()?.let {
                put("lineHeight", it)
            }
            // Forward the resolved font face so ProseMirrorConverter renders
            // headings and bold/italic marks in the *selected* family (not the
            // built-in fallback). fontWeight/fontStyle are passed through and
            // parsed by FaceContext; fontFamily is the structured ref.
            rawStyles["fontFamily"]?.let { put("fontFamily", it) }
            rawStyles["fontWeight"]?.let { put("fontWeight", it) }
            rawStyles["fontStyle"]?.let { put("fontStyle", it) }
        }

        // Convert ProseMirror content to iText elements
        @Suppress("UNCHECKED_CAST")
        val content = node.props?.get("content") as? Map<String, Any>
        val elements = context.proseMirrorConverter.convert(
            content,
            context.effectiveData,
            context.loopContext,
            context.fontCache,
            resolvedStyles,
            context.bookmarkCollector,
        )

        for (element in elements) {
            div.add(element)
        }

        return listOf(div)
    }
}
