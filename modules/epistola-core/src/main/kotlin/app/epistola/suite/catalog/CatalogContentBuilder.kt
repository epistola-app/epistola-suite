// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.DataExampleEntry
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.FontRef
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.catalog.protocol.VariantEntry
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** The canonical content of a catalog, independent of release metadata. */
data class CatalogContent(
    val catalog: CatalogInfo,
    val resourceEntries: List<ResourceEntry>,
    /** `"$type/$slug"` -> detail. */
    val resourceDetails: Map<String, ResourceDetail>,
    val dependencies: List<DependencyRef>?,
    /** The catalogs this one declares it depends on, each with the release it is locked or pinned to. */
    val catalogDependencies: List<CatalogDependency>? = null,
    /** Asset content filename (the part after `./resources/asset/`) -> bytes. */
    val assetContents: Map<String, ByteArray>,
    /**
     * Who released it: this installation for content it authors, the publisher's own for a release
     * installed from an archive. Part of the fingerprint, so an installed release keeps it.
     */
    val publisher: PublisherInfo = PublisherInfo(name = "Epistola"),
) {
    fun toManifest(
        release: ReleaseInfo,
        publisher: PublisherInfo = this.publisher,
    ): CatalogManifest = CatalogManifest(
        schemaVersion = CATALOG_SCHEMA_VERSION,
        catalog = catalog,
        publisher = publisher,
        release = release,
        resources = resourceEntries,
        dependencies = dependencies,
        catalogDependencies = catalogDependencies,
    )
}

/**
 * Builds the canonical content of a catalog — the manifest resource entries,
 * per-resource detail payloads, cross-catalog dependencies and asset binaries —
 * from the live database rows.
 *
 * Single source of truth shared by [ExportCatalogZip][app.epistola.suite.catalog.commands.ExportCatalogZip]
 * and [CatalogFingerprintService], so "the bytes you export" and "the bytes you
 * fingerprint" are identical by construction.
 */
@Component
class CatalogContentBuilder(
    private val objectMapper: ObjectMapper,
    private val jdbi: Jdbi,
    private val dependencyStore: CatalogDependencyStore,
    private val versionResources: app.epistola.suite.catalog.revisions.VersionResourceReader,
    private val assetContentStore: app.epistola.suite.storage.AssetContentStore,
) {
    fun build(catalog: CatalogId): CatalogContent {
        val found = GetCatalog(catalog).query()
            ?: throw IllegalArgumentException("Catalog not found: ${catalog.address}")

        // Every resource of the version, whatever its type (ADR 0039), read as a release reads its own:
        // as stored, which is how it travels, with the name qualifiers an earlier wire v8 draft let a
        // reference carry dropped. So a draft exports what the release cut from it exports.
        val read = jdbi.withHandle<List<app.epistola.suite.catalog.revisions.VersionResourceReader.VersionResource>, Exception> { versionResources.read(it, catalog) }
        val resourceEntries = read.map { ResourceEntry(type = it.type, key = it.key, name = it.name, description = it.description, detailUrl = "./resources/${it.type}/${it.key}.json") }
        val resourceDetails = read.associateTo(LinkedHashMap()) { resource ->
            val root = resource.resource
            ResourceReferenceSites.dropNameQualifiers(root)
            // Wire v8 never names the catalog itself (contract `2.0.0-alpha.4`): a reference into its own
            // catalog travels relative, an image's too, which is stored with it (an unqualified image is
            // looked up tenant-wide here).
            ResourceReferenceSites.relativizeOwnCatalog(root, catalog.key.value)
            ResourceReferenceSites.releaseOwnImages(root, catalog.key.value)
            // The catalog has one wire version: every detail (and the manifest) carries CATALOG_SCHEMA_VERSION.
            val envelope = objectMapper.createObjectNode().put("schemaVersion", CATALOG_SCHEMA_VERSION)
            envelope.set("resource", root)
            resource.contentKey to objectMapper.treeToValue(envelope, ResourceDetail::class.java)
        }
        val resources = resourceDetails.values.map { it.resource }

        // Keyed by the archive path each binary takes, which is what the canonicaliser and the
        // writer both ask for. An image's bytes are stored under its hash.
        val assetContents = LinkedHashMap<String, ByteArray>()
        for (image in resources.filterIsInstance<ImageResource>()) {
            val bytes = assetContentStore.get(image.contentHash)?.content?.use { it.readAllBytes() } ?: continue
            assetContents[image.contentPath()] = bytes
        }

        return CatalogContent(
            catalog = found.catalogMetadata.toCatalogInfo(
                slug = catalog.key.value,
                name = found.name,
                description = found.description,
                // The catalog's presentation is catalog-wide, its images belong to a version: only
                // those this version holds are named (any catalog can hold several versions, ADR 0032).
                availableAssetSlugs = resources.filterIsInstance<ImageResource>().mapTo(mutableSetOf()) { it.key },
                namespace = found.namespace,
                registry = found.registry,
            ).withThemeSettings(catalog),
            resourceEntries = resourceEntries,
            resourceDetails = resourceDetails,
            dependencies = findCrossCatalogDependencies(
                resources.filterIsInstance<TemplateResource>(),
                resources.filterIsInstance<StencilResource>(),
                resources.filterIsInstance<AttributeResource>(),
                resources.filterIsInstance<ThemeResource>(),
                resourceEntries,
                catalog.key.value,
            ),
            catalogDependencies = jdbi.withHandle<List<CatalogDependency>, Exception> { handle ->
                dependencyStore.declarations(handle, catalog).map { it.toWire() }
            }.ifEmpty { null },
            assetContents = withFaceBinaries(assetContents, catalog, resources.filterIsInstance<FontResource>()),
        )
    }

    /**
     * [eager] with the files of [fonts]' faces, each the font's own binary (ADR 0033), by archive path. Read when
     * asked for: a font's faces can be megabytes (the system fonts are 14 MB), and most builds (a
     * fingerprint, the next version's changes) never read them; an archive does.
     */
    private fun withFaceBinaries(eager: Map<String, ByteArray>, catalog: CatalogId, fonts: List<FontResource>): Map<String, ByteArray> {
        val faces = fonts.flatMap { it.variants }.associateBy { it.contentPath() }
        if (faces.isEmpty()) return eager
        val stored = jdbi.withHandle<Set<String>, Exception> { handle ->
            handle.createQuery("SELECT DISTINCT binary_hash FROM resource_binaries WHERE tenant_key = :t AND binary_hash IN (<hashes>)")
                .bind("t", catalog.tenantKey).bindList("hashes", faces.values.map { it.contentHash }.distinct())
                .mapTo(String::class.java).set()
        }
        val lazy: Map<String, () -> ByteArray?> = faces.filterValues { it.contentHash in stored }
            .mapValues { (_, face) -> { faceBytes(catalog.tenantKey, face.contentHash) } }
        return LazyBinaries(eager.mapValues { (_, bytes) -> { bytes } } + lazy)
    }

    /** The bytes of the face stored under [contentHash], as a content of [tenant] links them. */
    private fun faceBytes(tenant: TenantKey, contentHash: String): ByteArray? = jdbi.withHandle<ByteArray?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT c.content FROM resource_binaries b
            JOIN asset_content c ON c.content_hash = b.binary_hash
            WHERE b.tenant_key = :t AND b.binary_hash = :hash
            LIMIT 1
            """,
        ).bind("t", tenant).bind("hash", contentHash).mapTo(ByteArray::class.java).findOne().orElse(null)
    }

    /** Bytes by path, each read the first time it is asked for. */
    private class LazyBinaries(private val loaders: Map<String, () -> ByteArray?>) : AbstractMap<String, ByteArray>() {
        private val loaded = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        override fun get(key: String): ByteArray? = loaders[key]?.let { loader -> loaded[key] ?: loader()?.also { loaded[key] = it } }

        override fun containsKey(key: String): Boolean = key in loaders

        override val entries: Set<Map.Entry<String, ByteArray>>
            get() = loaders.keys.mapNotNull { key -> get(key)?.let { java.util.AbstractMap.SimpleImmutableEntry(key, it) } }.toSet()
    }

    /**
     * How the references of catalog [catalogKey] travel: stored ones are absolute, so relocation
     * cannot change their meaning; exported ones are relative to their own catalog, so the ZIP stays
     * installable under a different key. A reference into another catalog names it by the alias its
     * dependency list declares (contract ADR 0003, ADR 0034), which in this installation is always
     * that catalog's key ([app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration.alias]),
     * so a stored reference already names it; the `namespace` and `registry` an earlier draft of wire
     * v8 let it carry are dropped, from content stored or retained before.
     */
    class Outbound(private val catalogKey: String) {
        /** [document] (template, stencil or theme JSON) with its references as they travel. */
        fun content(document: JsonNode): JsonNode = document.deepCopy().also { root ->
            ResourceReferenceSites.relativizeOwnCatalog(root, catalogKey)
            ResourceReferenceSites.dropNameQualifiers(root)
        }
    }

    /**
     * Scan template models for references to resources NOT in this catalog's
     * manifest. Collect them as cross-catalog dependencies. No DB queries.
     */
    private fun findCrossCatalogDependencies(
        templates: List<TemplateResource>,
        stencils: List<StencilResource>,
        attributes: List<AttributeResource>,
        themes: List<ThemeResource>,
        manifestResources: List<ResourceEntry>,
        catalogKey: String,
    ): List<DependencyRef>? {
        val ownResources = manifestResources.map { "${it.type}:${it.key}" }.toSet()
        val isOwn = { type: String, key: String -> "$type:$key" in ownResources }
        val dependencies = mutableSetOf<DependencyRef>()
        for (theme in themes) {
            dependencies += DependencyScanner.themeCrossCatalogRefs(theme.documentStyles, theme.blockStylePresets, catalogKey, isOwn)
        }
        for (attribute in attributes) {
            val binding = attribute.codeListBinding ?: continue
            val target = binding.catalogKey ?: continue
            if (target == catalogKey) continue
            dependencies.add(DependencyRef.CodeList(catalogKey = target, key = binding.key))
        }
        for (template in templates) {
            val themeCatalog = template.themeCatalogKey
            if (template.themeKey != null && !isOwn("theme", template.themeKey!!) && themeCatalog != null && themeCatalog != catalogKey) {
                dependencies.add(DependencyRef.Theme(catalogKey = themeCatalog, key = template.themeKey!!))
            }
            dependencies += DependencyScanner.crossCatalogRefs(template.templateModel, catalogKey, isOwn)
            template.variants.mapNotNull { it.templateModel }.forEach { dependencies += DependencyScanner.crossCatalogRefs(it, catalogKey, isOwn) }
        }
        stencils.forEach { dependencies += DependencyScanner.crossCatalogRefs(it.content, catalogKey, isOwn) }

        return dependencies.toList().ifEmpty { null }
    }
}

/**
 * The catalog details with the version's theme settings (contract ADR 0007): its default theme,
 * named by the alias of its catalog when it lives in another one (that catalog's key, see
 * [CatalogContentBuilder.Outbound]), and the presets it declares. Absent ones leave the details, and
 * so the fingerprint, as they were.
 */
private fun CatalogInfo.withThemeSettings(catalog: CatalogId): CatalogInfo {
    val settings = app.epistola.suite.catalog.releases.GetCatalogThemeSettings(catalog).query()
    if (settings.defaultTheme == null && settings.presets.isEmpty()) return this
    val theme = settings.defaultTheme?.let { theme ->
        if (theme.catalogKey == catalog.key) {
            app.epistola.catalog.protocol.CatalogThemeRef(key = theme.themeKey.value)
        } else {
            app.epistola.catalog.protocol.CatalogThemeRef(key = theme.themeKey.value, catalogKey = theme.catalogKey.value)
        }
    }
    return copyWithMetadata(
        defaultTheme = theme,
        presets = settings.presets.map { app.epistola.catalog.protocol.CatalogPresetDeclaration(it.key, it.name, it.description) },
    )
}
