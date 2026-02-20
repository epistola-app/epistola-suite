package app.epistola.generation.pdf

/**
 * Resolves asset binary content by asset ID.
 * The generation module stays free of tenant/JDBI concepts — tenant scoping
 * is the caller's responsibility when constructing the resolver.
 */
fun interface AssetResolver {
    fun resolve(assetId: String): AssetResolution?
}

data class AssetResolution(val content: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssetResolution) return false
        return mimeType == other.mimeType && content.contentEquals(other.content)
    }

    override fun hashCode(): Int = mimeType.hashCode()
}
