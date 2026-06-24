package app.epistola.generation.pdf

import app.epistola.generation.ProseMirrorConverter
import app.epistola.generation.SystemParameterRegistry
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Node
import app.epistola.template.model.PageSettings
import app.epistola.template.model.TemplateDocument
/**
 * Context passed to node renderers during PDF generation.
 */
data class RenderContext(
    val data: Map<String, Any?>,
    val loopContext: Map<String, Any?> = emptyMap(),
    val documentStyles: DocumentStyles? = null,
    val expressionEvaluator: CompositeExpressionEvaluator,
    val proseMirrorConverter: ProseMirrorConverter,
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
    /** Controls error handling behavior (fail vs placeholder). */
    val renderMode: RenderMode = RenderMode.STRICT,
    /** Versioned rendering defaults (font sizes, spacing, borders, etc.) */
    val renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
    /** Theme-configurable spacing base unit in points (see [SpacingScale]). */
    val spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT,
    /**
     * Theme-resolved page settings, used as the middle layer of the
     * pageSettings cascade between the template's `pageSettingsOverride`
     * (highest priority) and `renderingDefaults.defaultPageSettings`
     * (lowest). Null when no theme is in play.
     */
    val resolvedPageSettings: PageSettings? = null,
    /** System parameters injected by the rendering engine (e.g., page number in headers/footers). */
    val systemParams: Map<String, Any?> = emptyMap(),
    /**
     * Named parameter scopes exposed to descendants. Each entry is `alias → values`,
     * where the alias is the top-level key the scope appears under in [effectiveData]
     * (default alias is "params"). Pushed by any node renderer that supports
     * parameters (today: stencil; future: snippet, fragment, …). Nested nodes with
     * different aliases coexist; nodes sharing the same alias intentionally shadow.
     */
    val parameterScopes: Map<String, Map<String, Any?>> = emptyMap(),
    /**
     * Pluggable schema lookup for parametrised nodes. Returns the JSON Schema
     * (as a JSON-structured `Map<String, Any?>`) describing the node's
     * parameters, or null if the node has none. Production wiring delegates to
     * the Spring `NodeParameterSchemaProviderRegistry` in `epistola-core`;
     * tests typically pass `{ _, _ -> null }` (the default) or an inline lambda.
     *
     * Component-agnostic by design: stencils answer with their per-instance
     * snapshot prop, future static-parametrised components answer with a constant.
     */
    val parameterSchemaProvider: (Node, TemplateDocument) -> Map<String, Any?>? = { _, _ -> null },
    /** Pre-calculated total page count from two-pass rendering. Null during first pass or single-pass rendering. */
    val totalPages: Int? = null,
    /**
     * Collects headings encountered during rendering so the renderer can
     * build a nested document outline / bookmarks for accessibility
     * (WCAG PDF2). Shared (mutable) across the node tree for one render.
     */
    val bookmarkCollector: MutableList<BookmarkEntry> = mutableListOf(),
    /**
     * Stencil IDs of every stencil node we are currently inside. Pushed by
     * [StencilNodeRenderer] when entering a stencil. A duplicate is the
     * defence-in-depth recursion catch; the editor and server-side validator
     * already reject documents that would recurse, so this should never trigger
     * in normal flow.
     */
    val ancestorStencilIds: Set<String> = emptySet(),
    /**
     * Inherited styles from the parent node, used for CSS-like style inheritance.
     * Initialized from document styles (inheritable keys only) and updated as we
     * traverse the node tree — each node's resolved inheritable styles become the
     * inherited styles for its children.
     */
    val inheritedStyles: Map<String, Any> = documentStyles
        ?.filterKeys { it in StyleApplicator.INHERITABLE_KEYS }
        ?: emptyMap(),
    /**
     * Y position (points, from the page top) where page-1 body content begins:
     * the resolved first-page band = page top margin + the effective,
     * content-measured first-page header height (+ any first-page spacer). Set by
     * the renderer when it lays out the body so a hoisted address block reserves
     * its window space *relative to the real header height* instead of
     * re-deriving it from the raw `height` prop (which ignores auto-grow). Null
     * outside body layout, where the address block falls back to its own estimate.
     */
    val bodyContentTopPt: Float? = null,
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
     * Data map with engine-provided scopes merged in. System parameters live
     * under `sys.*`; each named entry in [parameterScopes] (default alias
     * `params`) is exposed as a top-level key. Returns the original [data]
     * map when no engine scopes are set.
     *
     * Note: User data keys colliding with `sys` or with a parameter alias
     * are overwritten by the engine values. These namespaces are reserved.
     */
    val effectiveData: Map<String, Any?>
        get() {
            if (systemParams.isEmpty() && parameterScopes.isEmpty()) return data
            var result: Map<String, Any?> = data
            if (systemParams.isNotEmpty()) result = result + ("sys" to systemParams)
            for ((alias, values) in parameterScopes) {
                result = result + (alias to values)
            }
            return result
        }

    // The "effective page margins" cascade is per-side and walks
    // overrideNode → root → template override → theme → engine defaults.
    // Use `effectivePageMarginPt` / `effectivePageSettingsMarginMm` in
    // NodeRendererUtils which handles nullable individual sides correctly.

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
