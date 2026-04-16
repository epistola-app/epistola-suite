package app.epistola.suite.catalog.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

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
    val dependencies: List<DependencyRef>? = null,
)

/**
 * A reference to a resource that this catalog depends on.
 * Sealed hierarchy ensures type-safe construction:
 * - Themes and stencils are catalog-scoped (require catalogKey)
 * - Assets are tenant-global (just the UUID)
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = DependencyRef.Theme::class, name = "theme"),
    JsonSubTypes.Type(value = DependencyRef.Stencil::class, name = "stencil"),
    JsonSubTypes.Type(value = DependencyRef.Asset::class, name = "asset"),
)
sealed class DependencyRef {
    abstract val slug: String

    data class Theme(val catalogKey: String, override val slug: String) : DependencyRef()
    data class Stencil(val catalogKey: String, override val slug: String) : DependencyRef()
    data class Asset(override val slug: String) : DependencyRef()
}

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
