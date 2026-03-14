package app.epistola.template.model

import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

object DefaultStyleSystem {
    private const val RESOURCE_PATH = "style-system/default-style-system.json"

    private val objectMapper = jsonMapper {
        addModule(kotlinModule())
    }

    val data: StyleSystem by lazy {
        val stream = DefaultStyleSystem::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
            ?: error("Style system resource not found: $RESOURCE_PATH")
        stream.use { objectMapper.readValue<StyleSystem>(it) }
    }

    val canonicalPropertyKeys: Set<String> by lazy {
        data.canonicalProperties.mapTo(linkedSetOf()) { it.key }
    }

    val inheritablePropertyKeys: Set<String> by lazy {
        data.canonicalProperties
            .asSequence()
            .filter { it.inheritable }
            .map { it.key }
            .toCollection(linkedSetOf())
    }

    /**
     * Typography scale with font size multipliers for text elements.
     * Applied as: baseFontSize × multiplier = effectiveFontSize
     */
    val typographyScale: StyleSystem.TypographyScale by lazy {
        data.typographyScale
    }

    /**
     * Element types supported by the typography scale system.
     */
    enum class TypographyElementType {
        PARAGRAPH,
        HEADING1,
        HEADING2,
        HEADING3,
    }

    /**
     * Calculate the effective font size for a given text element.
     *
     * If an explicit font size is provided, it takes precedence.
     * Otherwise, calculates from: baseFontSize × typographyScale multiplier.
     *
     * @param elementType The type of text element (paragraph, heading1, heading2, heading3)
     * @param baseFontSizePt The base font size in points (e.g., 12f)
     * @param explicitFontSize Optional explicit font size string (e.g., "24pt")
     * @return The effective font size in points
     */
    fun calculateFontSize(
        elementType: TypographyElementType,
        baseFontSizePt: Float,
        explicitFontSize: String?,
    ): Float {
        // If explicit font size is set, parse and use it
        explicitFontSize?.let {
            return parseFontSize(it, baseFontSizePt) ?: baseFontSizePt
        }

        // Get multiplier from typography scale
        val multiplier = when (elementType) {
            TypographyElementType.PARAGRAPH -> typographyScale.paragraph.fontSizeMultiplier.toFloat()
            TypographyElementType.HEADING1 -> typographyScale.heading1.fontSizeMultiplier.toFloat()
            TypographyElementType.HEADING2 -> typographyScale.heading2.fontSizeMultiplier.toFloat()
            TypographyElementType.HEADING3 -> typographyScale.heading3.fontSizeMultiplier.toFloat()
        }

        // Calculate effective size
        return baseFontSizePt * multiplier
    }

    /**
     * Get the font size multiplier for a given text element type.
     *
     * @param elementType The type of text element
     * @return The multiplier (e.g., 2.0 for heading1)
     */
    fun getFontSizeMultiplier(elementType: TypographyElementType): Float = when (elementType) {
        TypographyElementType.PARAGRAPH -> typographyScale.paragraph.fontSizeMultiplier.toFloat()
        TypographyElementType.HEADING1 -> typographyScale.heading1.fontSizeMultiplier.toFloat()
        TypographyElementType.HEADING2 -> typographyScale.heading2.fontSizeMultiplier.toFloat()
        TypographyElementType.HEADING3 -> typographyScale.heading3.fontSizeMultiplier.toFloat()
    }

    /**
     * Parse a font size string to points.
     * Supports: pt (direct), px (converted), em/rem (relative to base)
     *
     * @param fontSize The font size string (e.g., "12pt", "16px", "1.5em")
     * @param baseFontSizePt The base font size for relative calculations
     * @return The font size in points, or null if parsing fails
     */
    private fun parseFontSize(fontSize: String, baseFontSizePt: Float): Float? = when {
        fontSize.endsWith("pt") -> fontSize.removeSuffix("pt").toFloatOrNull()
        fontSize.endsWith("px") -> fontSize.removeSuffix("px").toFloatOrNull()?.times(0.75f)
        fontSize.endsWith("em") -> fontSize.removeSuffix("em").toFloatOrNull()?.times(baseFontSizePt)
        fontSize.endsWith("rem") -> fontSize.removeSuffix("rem").toFloatOrNull()?.times(baseFontSizePt)
        else -> fontSize.toFloatOrNull()
    }
}
