package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.List
import com.itextpdf.layout.element.ListItem
import com.itextpdf.layout.properties.ListNumberingType

/**
 * Renders a "datalist" node as a formatted list (bulleted/numbered) driven by data.
 *
 * Similar to [LoopNodeRenderer] but wraps each iteration's content in a [ListItem]
 * inside an iText [List], producing proper bullet/numbering markers.
 *
 * Props:
 * - `expression`: Expression object (with "raw" and optional "language" keys)
 * - `itemAlias`: String (defaults to "item")
 * - `indexAlias`: String (optional)
 * - `listType`: String (bullet, decimal, lower-alpha, upper-alpha, lower-roman, upper-roman, none)
 */
class DataListNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): kotlin.collections.List<IElement> {
        val expression = extractExpression(node.props?.get("expression"), context.defaultExpressionLanguage)
            ?: return emptyList()

        val iterable = context.expressionEvaluator.evaluateIterable(
            expression,
            context.effectiveData,
            context.loopContext,
        )

        if (iterable.isEmpty()) return emptyList()

        val itemAlias = node.props?.get("itemAlias") as? String ?: "item"
        val indexAlias = node.props?.get("indexAlias") as? String
        val listType = node.props?.get("listType") as? String ?: "bullet"

        val list = createList(listType)
        list.setMarginBottom(context.renderingDefaults.listMarginBottom)
        list.setMarginLeft(context.renderingDefaults.listMarginLeft)

        // Apply block styles (margins, background, etc.)
        StyleApplicator.applyStylesWithPreset(
            list,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.inheritedStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("datalist"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )

        // Compute inherited styles once for all iterations
        val inheritedContext = context.withInheritedStylesFrom(node)

        for ((index, item) in iterable.withIndex()) {
            val itemContext = context.loopContext.toMutableMap()
            itemContext[itemAlias] = item
            itemContext["${itemAlias}_index"] = index
            itemContext["${itemAlias}_first"] = (index == 0)
            itemContext["${itemAlias}_last"] = (index == iterable.size - 1)
            if (indexAlias != null) {
                itemContext[indexAlias] = index
            }

            val childContext = inheritedContext.copy(loopContext = itemContext)
            val childElements = registry.renderSlots(node, document, childContext)

            val listItem = ListItem()
            listItem.setMarginBottom(context.renderingDefaults.listItemMarginBottom)
            for (element in childElements) {
                when (element) {
                    is IBlockElement -> listItem.add(element)
                    is Image -> listItem.add(element)
                    else -> Unit
                }
            }
            list.add(listItem)
        }

        return listOf(list)
    }

    private fun createList(listType: String): List = when (listType) {
        "decimal" -> List(ListNumberingType.DECIMAL)
        "lower-alpha" -> List(ListNumberingType.ENGLISH_LOWER)
        "upper-alpha" -> List(ListNumberingType.ENGLISH_UPPER)
        "lower-roman" -> List(ListNumberingType.ROMAN_LOWER)
        "upper-roman" -> List(ListNumberingType.ROMAN_UPPER)
        "none" -> List().apply { setListSymbol("") }
        else -> List() // bullet (default)
    }
}
