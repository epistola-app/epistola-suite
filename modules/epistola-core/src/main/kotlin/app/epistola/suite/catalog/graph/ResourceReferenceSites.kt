// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.graph

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

private const val CATALOG_KEY = "catalogKey"

/**
 * The catalog-resource reference shapes that can appear inside template, stencil, and theme JSON.
 *
 * This enum plus [ResourceReferenceSites] is the single authority for "what is a reference" in
 * embedded content. Every consumer that reads or rewrites embedded references — the resource
 * graph, catalog relocation, and catalog export — traverses through it, so they cannot disagree
 * about which nodes carry references or how an unqualified one resolves. A new embedded reference
 * shape is added here, never as another walker.
 */
enum class ReferenceSiteKind(
    val type: CatalogResourceType,
    /** Edge kind reported by the resource graph. */
    val wireKind: String,
    val semantics: ReferenceSemantics,
    /** Field on the holder object carrying the referenced resource key. */
    val keyField: String,
    /**
     * Whether an unqualified reference resolves against the catalog containing it. Assets are
     * looked up tenant-globally instead, so relocation never qualifies them with a catalog.
     */
    val relativeWhenUnqualified: Boolean,
    /** `type` a node must declare for a reference carried in its `props`, else null. */
    val nodeType: String? = null,
    /** Field holding the reference object, for references not carried in a node's `props`. */
    val containerField: String? = null,
) {
    STENCIL_INSERTION(CatalogResourceType.STENCIL, "stencil-insertion", ReferenceSemantics.PROVENANCE, "stencilId", true, nodeType = "stencil"),
    IMAGE_ASSET(CatalogResourceType.ASSET, "image-asset", ReferenceSemantics.RUNTIME, "assetId", false, nodeType = "image"),
    THEME_OVERRIDE(CatalogResourceType.THEME, "theme-override", ReferenceSemantics.RUNTIME, "themeId", true, containerField = "themeRef"),
    FONT_FAMILY(CatalogResourceType.FONT, "font-family", ReferenceSemantics.RUNTIME, "slug", true, containerField = "fontFamily"),
    ;

    internal fun locationIn(path: String) = if (nodeType != null) "$path.props.$keyField" else "$path.$containerField"
}

/**
 * One reference occurrence located in a JSON payload, keeping a handle on the object that carries
 * it so callers can rewrite the reference in place instead of re-walking the tree.
 */
class ReferenceSite internal constructor(
    val kind: ReferenceSiteKind,
    /** The object holding the reference fields: a node's `props`, or a `themeRef`/`fontFamily`. */
    private val holder: ObjectNode,
    /** Dotted path of the reference within the payload, e.g. `templateModel.props.stencilId`. */
    val location: String,
) {
    val key: String get() = holder.path(kind.keyField).stringValue()

    /** The explicitly authored catalog, or null when the reference is unqualified. */
    val catalogKey: String? get() = holder.path(CATALOG_KEY).textOrNull()

    /** The pinned version a stencil insertion carries, when it has one. */
    val pinnedVersion: Int? get() = holder.path("version").takeUnless { it.isMissingNode || it.isNull }?.asInt()

    fun setCatalogKey(value: String) {
        holder.put(CATALOG_KEY, value)
    }

    fun setKey(value: String) {
        holder.put(kind.keyField, value)
    }
}

/** Locates every [ReferenceSite] in a template, stencil, or theme JSON payload. */
object ResourceReferenceSites {
    /**
     * @param path prefix for reported [ReferenceSite.location]s, naming the column being scanned
     *   (`templateModel`, `content`, `documentStyles`, …).
     */
    fun scan(root: JsonNode, path: String = ""): List<ReferenceSite> = buildList { collect(root, path, this) }

    private fun collect(node: JsonNode, path: String, into: MutableList<ReferenceSite>) {
        if (node is ObjectNode) {
            val nodeType = node.path("type").textOrNull()
            for (kind in ReferenceSiteKind.entries) {
                val holder = when {
                    kind.nodeType != null -> node.path("props").takeIf { nodeType == kind.nodeType }
                    else -> node.path(kind.containerField!!)
                } as? ObjectNode ?: continue
                if (holder.path(kind.keyField).textOrNull() == null) continue
                into += ReferenceSite(kind, holder, kind.locationIn(path))
            }
            node.properties().forEach { (key, child) -> collect(child, "$path.$key", into) }
        } else if (node.isArray) {
            node.forEachIndexed { index, child -> collect(child, "$path[$index]", into) }
        }
    }
}

internal fun JsonNode.textOrNull(): String? = takeUnless { isMissingNode || isNull || !isString }?.stringValue()?.takeIf { it.isNotBlank() }
