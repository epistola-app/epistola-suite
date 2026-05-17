package app.epistola.generation.pdf

import app.epistola.catalog.protocol.FontRef

/**
 * The single source of truth for parsing a style map's `fontFamily` value
 * into a [FontRef]. The wire shape is the structured object
 * `{ "slug": "...", "catalogKey": "..."? }` (the `codeListBinding`
 * convention); `slug` is required and non-blank, `catalogKey` optional.
 *
 * Anything else (a legacy CSS-stack string, a non-object, a slug-less map)
 * yields `null`. Callers that want to surface that as a warning wrap this
 * (see [StyleApplicator.parseFontRef]); callers that just need the ref
 * (catalog dependency scanning) use it directly. Having one parser keeps the
 * generation renderer and the catalog dependency scanner from drifting on
 * the wire shape.
 */
object FontRefs {
    fun parse(value: Any?): FontRef? {
        val map = value as? Map<*, *> ?: return null
        val slug = (map["slug"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return FontRef(catalogKey = map["catalogKey"] as? String, slug = slug)
    }
}
