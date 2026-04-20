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
    )

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

        return Dependencies(
            themeRefs = themeRefs,
            stencilRefs = stencilRefs,
            assetRefs = assetRefs,
            attributeKeys = variantAttributes,
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
    )
}
