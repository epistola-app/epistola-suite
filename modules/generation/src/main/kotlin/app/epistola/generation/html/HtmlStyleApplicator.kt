package app.epistola.generation.html

import app.epistola.template.model.DocumentStyles

/**
 * Converts style maps to CSS inline style strings.
 * Mirrors the cascade logic of the PDF StyleApplicator.
 */
object HtmlStyleApplicator {

    val INHERITABLE_KEYS = setOf(
        "fontFamily",
        "fontSize",
        "fontWeight",
        "fontStyle",
        "color",
        "lineHeight",
        "letterSpacing",
        "textAlign",
    )

    /**
     * Resolves the full style cascade and returns a CSS inline style string.
     *
     * Cascade order (lowest to highest priority):
     * 1. Component default styles
     * 2. Inheritable document styles
     * 3. Theme block preset
     * 4. Block inline styles
     */
    fun buildStyleAttribute(
        blockInlineStyles: Map<String, Any>?,
        blockStylePreset: String?,
        blockStylePresets: Map<String, Map<String, Any>>,
        documentStyles: DocumentStyles?,
        defaultStyles: Map<String, Any>? = null,
    ): String {
        val merged = mutableMapOf<String, Any>()

        // 1. Component defaults
        defaultStyles?.let { merged.putAll(it) }

        // 2. Inheritable document styles
        documentStyles?.let { docStyles ->
            val inheritable = docStyles.filterKeys { it in INHERITABLE_KEYS }
            merged.putAll(inheritable)
        }

        // 3. Theme preset
        blockStylePreset?.let { blockStylePresets[it] }?.let { merged.putAll(it) }

        // 4. Block inline styles (highest priority)
        blockInlineStyles?.let { merged.putAll(it) }

        return toCss(merged)
    }

    private fun toCss(styles: Map<String, Any>): String {
        val parts = mutableListOf<String>()

        (styles["fontSize"] as? String)?.let { parts.add("font-size: $it") }
        (styles["fontFamily"] as? String)?.let { parts.add("font-family: $it") }
        (styles["fontWeight"] as? String)?.let { parts.add("font-weight: $it") }
        (styles["fontStyle"] as? String)?.let { parts.add("font-style: $it") }
        (styles["color"] as? String)?.let { parts.add("color: $it") }
        (styles["backgroundColor"] as? String)?.let { parts.add("background-color: $it") }
        (styles["textAlign"] as? String)?.let { parts.add("text-align: $it") }
        (styles["lineHeight"] as? String)?.let { parts.add("line-height: $it") }
        (styles["letterSpacing"] as? String)?.let { parts.add("letter-spacing: $it") }

        (styles["marginTop"] as? String)?.let { parts.add("margin-top: $it") }
        (styles["marginRight"] as? String)?.let { parts.add("margin-right: $it") }
        (styles["marginBottom"] as? String)?.let { parts.add("margin-bottom: $it") }
        (styles["marginLeft"] as? String)?.let { parts.add("margin-left: $it") }

        (styles["paddingTop"] as? String)?.let { parts.add("padding-top: $it") }
        (styles["paddingRight"] as? String)?.let { parts.add("padding-right: $it") }
        (styles["paddingBottom"] as? String)?.let { parts.add("padding-bottom: $it") }
        (styles["paddingLeft"] as? String)?.let { parts.add("padding-left: $it") }

        (styles["width"] as? String)?.let { parts.add("width: $it") }

        return parts.joinToString("; ")
    }
}
