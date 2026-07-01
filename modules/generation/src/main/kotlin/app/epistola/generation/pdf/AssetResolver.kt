package app.epistola.generation.pdf

/**
 * Resolves asset binary content by asset ID, optionally qualified by the
 * catalog the reference points at (assets are keyed by `(tenant, catalog, id)`,
 * so a cross-catalog image reference carries its own [catalogKey]).
 *
 * The generation module stays free of tenant/JDBI concepts — tenant scoping
 * is the caller's responsibility when constructing the resolver, and
 * [catalogKey] is a plain slug string rather than a core `CatalogKey`.
 */
fun interface AssetResolver {
    fun resolve(assetId: String, catalogKey: String?): AssetResolution?
}

data class AssetResolution(val content: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetResolution) return false
        return mimeType == other.mimeType && content.contentEquals(other.content)
    }

    override fun hashCode(): Int = mimeType.hashCode()
}
