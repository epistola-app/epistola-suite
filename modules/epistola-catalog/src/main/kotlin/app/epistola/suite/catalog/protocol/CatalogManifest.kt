package app.epistola.suite.catalog.protocol

/**
 * Wire format for the catalog manifest JSON served at a catalog URL.
 * Matches the schema defined in docs/exchange.md.
 */
data class CatalogManifest(
    val schemaVersion: Int,
    val catalog: CatalogInfo,
    val publisher: PublisherInfo,
    val release: ReleaseInfo,
    val compatibility: CompatibilityInfo? = null,
    val includes: List<IncludeEntry>? = null,
    val resources: List<ResourceEntry>,
)

data class CatalogInfo(
    val slug: String,
    val name: String,
    val description: String? = null,
)

data class PublisherInfo(
    val name: String,
    val url: String? = null,
)

data class ReleaseInfo(
    val version: String,
    val releasedAt: String? = null,
)

data class CompatibilityInfo(
    val epistolaVersions: String? = null,
)

data class IncludeEntry(
    val url: String,
    val description: String? = null,
)

data class ResourceEntry(
    val type: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val updatedAt: String? = null,
    val detailUrl: String,
    val compatibility: CompatibilityInfo? = null,
)
