// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument

/**
 * Expands the CSS shorthands `padding`, `margin` and `border` into the per-side keys the
 * renderer reads (`paddingTop`, …, `borderLeft`).
 *
 * The editor always writes per-side keys, but the style registry offers the shorthands and
 * documents authored over MCP or imported from a catalog use them. Before rendering-defaults
 * V6 they were ignored (see [RenderingDefaults.expandStyleShorthands]).
 *
 * `padding` and `margin` take one to four values in CSS order (top, right, bottom, left);
 * `border` is one side's shorthand (`"1pt solid #cbd5e1"`) applied to all four. A per-side key
 * in the same map wins over the shorthand, whatever the key order. A value that is not one to
 * four parts is dropped, as an unparseable per-side value would be.
 */
internal object StyleShorthands {

    private val SIDES = listOf("Top", "Right", "Bottom", "Left")
    private val SHORTHANDS = setOf("padding", "margin", "border")

    fun expand(styles: Map<String, Any>): Map<String, Any> {
        if (styles.keys.none { it in SHORTHANDS }) return styles

        val expanded = LinkedHashMap<String, Any>(styles.size + 8)
        for ((key, value) in styles) {
            if (key !in SHORTHANDS) expanded[key] = value
        }
        for (shorthand in SHORTHANDS) {
            val value = styles[shorthand] as? String ?: continue
            val sides = if (shorthand == "border") List(4) { value.trim() } else boxValues(value) ?: continue
            SIDES.forEachIndexed { index, side ->
                expanded.putIfAbsent("$shorthand$side", sides[index])
            }
        }
        return expanded
    }

    /** Expands every node's inline styles and every theme preset. */
    fun expandIn(document: TemplateDocument): TemplateDocument {
        var changed = false
        val nodes = document.nodes.mapValues { (_, node) ->
            val styles = node.styles ?: return@mapValues node
            if (styles.keys.none { it in SHORTHANDS }) return@mapValues node
            changed = true
            node.copy(styles = expand(styles.filterNonNullValues()))
        }
        return if (changed) document.copy(nodes = nodes) else document
    }

    fun expandIn(theme: ResolvedTheme): ResolvedTheme {
        if (theme.blockStylePresets.values.none { preset -> preset.keys.any { it in SHORTHANDS } }) return theme
        return theme.copy(blockStylePresets = theme.blockStylePresets.mapValues { (_, preset) -> expand(preset) })
    }

    private fun boxValues(value: String): List<String>? {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (parts.size) {
            1 -> List(4) { parts[0] }
            2 -> listOf(parts[0], parts[1], parts[0], parts[1])
            3 -> listOf(parts[0], parts[1], parts[2], parts[1])
            4 -> parts
            else -> null
        }
    }
}
