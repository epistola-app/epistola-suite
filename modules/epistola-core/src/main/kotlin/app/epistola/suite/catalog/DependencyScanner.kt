package app.epistola.suite.catalog

import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride

/**
 * Scans a TemplateDocument for references to themes, stencils, and image assets.
 * Used during catalog export to auto-include dependencies.
 */
object DependencyScanner {

    data class Dependencies(
        val themeRefs: Set<String> = emptySet(),
        val stencilRefs: Set<String> = emptySet(),
        val assetRefs: Set<String> = emptySet(),
        val attributeKeys: Set<String> = emptySet(),
        /**
         * Same-catalog code-list dependencies — slugs of code lists that an
         * attribute in this catalog binds to. Cross-catalog code-list
         * bindings are declared via `manifest.dependencies` instead and
         * checked at install-time, not auto-pulled here.
         */
        val codeListRefs: Set<String> = emptySet(),
        /**
         * Same-catalog font-family dependencies — slugs of font families that
         * a theme/template `fontFamily` style binds to within this catalog.
         * Cross-catalog font bindings are declared via `manifest.dependencies`
         * and checked at install-time, not auto-pulled here.
         */
        val fontRefs: Set<String> = emptySet(),
    )

    /** A `{ slug, catalogKey? }` font reference parsed from a `fontFamily` style value. */
    data class FontRefLite(val catalogKey: String?, val slug: String)

    /** Extracts the structured font reference from a style map's `fontFamily` value, if any. */
    fun fontRefIn(styles: Map<*, *>?): FontRefLite? {
        val ff = styles?.get("fontFamily") as? Map<*, *> ?: return null
        val slug = (ff["slug"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return FontRefLite(catalogKey = ff["catalogKey"] as? String, slug = slug)
    }

    /** Font references carried by a theme's document styles and block-style presets. */
    fun themeFontRefs(documentStyles: Map<String, Any?>?, blockStylePresets: Map<String, Any?>?): Set<FontRefLite> = buildSet {
        fontRefIn(documentStyles)?.let { add(it) }
        blockStylePresets?.values?.forEach { preset -> fontRefIn(preset as? Map<*, *>)?.let { add(it) } }
    }

    /** Font references carried by a template document's style override and inline node styles. */
    fun documentFontRefs(document: TemplateDocument): Set<FontRefLite> = buildSet {
        fontRefIn(document.documentStylesOverride)?.let { add(it) }
        for (node in document.nodes.values) fontRefIn(node.styles)?.let { add(it) }
    }

    fun scan(document: TemplateDocument, variantAttributes: Set<String> = emptySet()): Dependencies {
        val themeRefs = mutableSetOf<String>()
        val stencilRefs = mutableSetOf<String>()
        val assetRefs = mutableSetOf<String>()

        // Scan themeRef
        val themeRef = document.themeRef
        if (themeRef is ThemeRefOverride) {
            themeRefs.add(themeRef.themeId)
        }

        // Scan all nodes for stencils and images
        for (node in document.nodes.values) {
            when (node.type) {
                "stencil" -> {
                    val stencilId = node.props?.get("stencilId") as? String
                    if (stencilId != null) stencilRefs.add(stencilId)
                }
                "image" -> {
                    val assetId = node.props?.get("assetId") as? String
                    if (assetId != null) assetRefs.add(assetId)
                }
            }
        }

        // Same-catalog font refs (no explicit catalogKey) are auto-pulled like
        // code lists; cross-catalog ones are declared on manifest.dependencies.
        val fontRefs = documentFontRefs(document)
            .filter { it.catalogKey == null }
            .mapTo(mutableSetOf()) { it.slug }

        return Dependencies(
            themeRefs = themeRefs,
            stencilRefs = stencilRefs,
            assetRefs = assetRefs,
            attributeKeys = variantAttributes,
            fontRefs = fontRefs,
        )
    }

    /**
     * Merges multiple dependency sets into one.
     */
    fun merge(vararg deps: Dependencies): Dependencies = Dependencies(
        themeRefs = deps.flatMapTo(mutableSetOf()) { it.themeRefs },
        stencilRefs = deps.flatMapTo(mutableSetOf()) { it.stencilRefs },
        assetRefs = deps.flatMapTo(mutableSetOf()) { it.assetRefs },
        attributeKeys = deps.flatMapTo(mutableSetOf()) { it.attributeKeys },
        codeListRefs = deps.flatMapTo(mutableSetOf()) { it.codeListRefs },
        fontRefs = deps.flatMapTo(mutableSetOf()) { it.fontRefs },
    )
}
