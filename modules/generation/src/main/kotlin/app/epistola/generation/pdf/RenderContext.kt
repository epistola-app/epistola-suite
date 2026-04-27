package app.epistola.generation.pdf

import app.epistola.generation.SystemParameterRegistry
import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.PdfDocument

/**
 * Context passed to node renderers during PDF generation.
 */
data class RenderContext(
    val data: Map<String, Any?>,
    val loopContext: Map<String, Any?> = emptyMap(),
    val documentStyles: DocumentStyles? = null,
    val expressionEvaluator: CompositeExpressionEvaluator,
    val tipTapConverter: TipTapConverter,
    /** Default language for embedded expressions in text (e.g., "Hello {{name}}!") */
    val defaultExpressionLanguage: ExpressionLanguage = ExpressionLanguage.jsonata,
    /** Font cache scoped to this document */
    val fontCache: FontCache,
    /** Block style presets from theme (named style collections like CSS classes) */
    val blockStylePresets: Map<String, Map<String, Any>> = emptyMap(),
    /** The template document being rendered (for node/slot lookups) */
    val document: TemplateDocument,
    /** Optional asset resolver for loading image content during rendering */
    val assetResolver: AssetResolver? = null,
    /** Active PdfDocument for renderers that require document-bound conversion (e.g., SVG). */
    val pdfDocument: PdfDocument? = null,
    /** Versioned rendering defaults (font sizes, spacing, borders, etc.) */
    val renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
    /** Theme-configurable spacing base unit in points (see [SpacingScale]). */
    val spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT,
    /** System parameters injected by the rendering engine (e.g., page number in headers/footers). */
    val systemParams: Map<String, Any?> = emptyMap(),
    /** Pre-calculated total page count from two-pass rendering. Null during first pass or single-pass rendering. */
    val totalPages: Int? = null,
    /**
     * Inherited styles from the parent node, used for CSS-like style inheritance.
     * Initialized from document styles (inheritable keys only) and updated as we
     * traverse the node tree — each node's resolved inheritable styles become the
     * inherited styles for its children.
     */
    val inheritedStyles: Map<String, Any> = documentStyles
        ?.filterKeys { it in StyleApplicator.INHERITABLE_KEYS }
        ?: emptyMap(),
) {
    /**
     * Returns a copy of this context with updated inherited styles based on a node's
     * resolved styles. Only inheritable style properties are propagated to children.
     */
    fun withInheritedStylesFrom(node: app.epistola.template.model.Node): RenderContext {
        val resolved = StyleApplicator.resolveInheritedStyles(
            inheritedStyles,
            node.stylePreset,
            blockStylePresets,
            node.styles?.filterNonNullValues(),
        )
        return if (resolved === inheritedStyles) this else copy(inheritedStyles = resolved)
    }

    /**
     * Data map with system parameters merged under the `sys` key.
     * Returns the original [data] map when no system parameters are set.
     *
     * Note: If user data contains a top-level `sys` key, it will be
     * overwritten by system parameters. The `sys` namespace is reserved
     * for engine-provided values.
     */
    val effectiveData: Map<String, Any?>
        get() = if (systemParams.isEmpty()) data else data + mapOf("sys" to systemParams)

    /**
     * Returns a copy of this context with page-scoped system parameters injected.
     */
    fun withPageParams(pageNumber: Int, totalPages: Int): RenderContext = copy(
        systemParams = systemParams + SystemParameterRegistry.buildPageParams(pageNumber, totalPages),
    )

    /**
     * Returns a copy of this context with a pre-calculated total pages value.
     * Used for two-pass rendering where the total is determined in the first pass.
     * Injects `pages.total` into system params so it is available in body content.
     */
    fun withTotalPages(totalPages: Int): RenderContext = copy(
        totalPages = totalPages,
        systemParams = systemParams + SystemParameterRegistry.buildNestedMap(mapOf("pages.total" to totalPages)),
    )
}
