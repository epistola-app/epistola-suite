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
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.queries.ExportAssets
import app.epistola.suite.catalog.queries.ExportAttributes
import app.epistola.suite.catalog.queries.ExportCodeLists
import app.epistola.suite.catalog.queries.ExportFonts
import app.epistola.suite.catalog.queries.ExportStencils
import app.epistola.suite.catalog.queries.ExportThemes
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.StoredTemplate
import app.epistola.suite.templates.StoredVariant
import app.epistola.suite.templates.TemplateStore
import app.epistola.suite.templates.model.DataExamples
import app.epistola.suite.templates.model.TemplateDocument
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
    private val fontStore: app.epistola.suite.fonts.FontStore,
    private val templateStore: app.epistola.suite.templates.TemplateStore,
) {

    fun build(catalog: CatalogId): CatalogContent {
        val found = GetCatalog(catalog).query()
            ?: throw IllegalArgumentException("Catalog not found: ${catalog.address}")

        val outbound = Outbound(catalog.key.value)
        val templates = loadTemplates(catalog, outbound)
        val themes = ExportThemes(catalog.tenantKey, catalog = catalog).query().map { theme ->
            // A theme's own-catalog font references have always travelled as stored.
            objectMapper.treeToValue(outbound.withoutNameQualifiers(objectMapper.valueToTree(theme)), ThemeResource::class.java)
        }
        val stencils = ExportStencils(catalog.tenantKey, catalog = catalog).query().map { stencil ->
            stencil.copy(
                content = outbound.content(objectMapper.valueToTree(stencil.content)).let { objectMapper.treeToValue(it, TemplateDocument::class.java) },
            )
        }
        val attributes = ExportAttributes(catalog.tenantKey, catalog = catalog).query()
        val codeLists = ExportCodeLists(catalog.tenantKey, catalog = catalog).query()
        val fonts = ExportFonts(catalog.tenantKey, catalog = catalog).query()
        val assets = ExportAssets(catalog.tenantKey, catalog = catalog).query()

        val resourceEntries = mutableListOf<ResourceEntry>()
        val resourceDetails = LinkedHashMap<String, ResourceDetail>()

        fun addResource(type: String, slug: String, name: String, description: String?, resource: CatalogResource) {
            resourceEntries.add(
                ResourceEntry(type = type, key = slug, name = name, description = description, detailUrl = "./resources/$type/$slug.json"),
            )
            // The catalog has one wire version — stamp every detail (and the
            // manifest) with the same catalog-wide CATALOG_SCHEMA_VERSION.
            resourceDetails["$type/$slug"] = ResourceDetail(schemaVersion = CATALOG_SCHEMA_VERSION, resource = resource)
        }

        for (codeList in codeLists) addResource("codeList", codeList.key, codeList.name, codeList.description, codeList)
        for (font in fonts) addResource("font", font.key, font.name, null, font)
        for (attr in attributes) addResource("attribute", attr.key, attr.name, null, attr)
        for (theme in themes) addResource("theme", theme.key, theme.name, theme.description, theme)
        for (stencil in stencils) addResource("stencil", stencil.key, stencil.name, stencil.description, stencil)
        for (image in assets) addResource("image", image.key, image.name, null, image)
        // The manifest entry's description is the template's own (wire v8: equal when both are present).
        for (template in templates) addResource("template", template.key, template.name, template.description, template)

        // Keyed by the archive path each binary takes, which is what the canonicaliser and the
        // writer both ask for. It used to key by a filename parsed out of contentUrl and recover
        // the asset id by parsing that as a UUID -- neither survives wire v7, where a binary is
        // filed under its hash and an image's slug may be a readable name.
        val assetContents = LinkedHashMap<String, ByteArray>()
        for (detail in resourceDetails.values) {
            when (val resource = detail.resource) {
                is ImageResource -> {
                    val content = GetAssetContent(tenantId = catalog.tenantKey, assetId = AssetKey.of(resource.key), catalog = catalog).query()
                        ?: continue
                    assetContents[resource.contentPath()] = content.content
                }

                is FontResource -> Unit

                else -> Unit
            }
        }

        return CatalogContent(
            catalog = found.catalogMetadata.toCatalogInfo(
                slug = catalog.key.value,
                name = found.name,
                description = found.description,
                // The catalog's presentation is catalog-wide, its images belong to a version: only
                // those this version holds are named (any catalog can hold several versions, ADR 0032).
                availableAssetSlugs = assets.mapTo(mutableSetOf()) { it.key },
                namespace = found.namespace,
                registry = found.registry,
            ).withThemeSettings(catalog),
            resourceEntries = resourceEntries,
            resourceDetails = resourceDetails,
            dependencies = findCrossCatalogDependencies(templates, stencils, attributes, themes, resourceEntries, catalog.key.value),
            catalogDependencies = jdbi.withHandle<List<CatalogDependency>, Exception> { handle ->
                dependencyStore.declarations(handle, catalog).map { it.toWire() }
            }.ifEmpty { null },
            assetContents = withFaceBinaries(assetContents, catalog, fonts),
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
            .mapValues { (_, face) -> { jdbi.withHandle<ByteArray?, Exception> { fontStore.bytes(it, catalog.tenantKey, face.contentHash) } } }
        return LazyBinaries(eager.mapValues { (_, bytes) -> { bytes } } + lazy)
    }

    /** Bytes by path, each read the first time it is asked for. */
    private class LazyBinaries(private val loaders: Map<String, () -> ByteArray?>) : AbstractMap<String, ByteArray>() {
        private val loaded = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        override fun get(key: String): ByteArray? = loaders[key]?.let { loader -> loaded[key] ?: loader()?.also { loaded[key] = it } }

        override fun containsKey(key: String): Boolean = key in loaders

        override val entries: Set<Map.Entry<String, ByteArray>>
            get() = loaders.keys.mapNotNull { key -> get(key)?.let { java.util.AbstractMap.SimpleImmutableEntry(key, it) } }.toSet()
    }

    private fun loadTemplates(
        catalog: CatalogId,
        outbound: Outbound,
    ): List<TemplateResource> {
        // A draft holds each template as its content (ADR 0033).
        val templates = jdbi.withHandle<List<StoredTemplate>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT t.id
                FROM current_templates t
                WHERE t.tenant_key = :tenantKey AND t.catalog_id = ${catalogAt("tenantKey", "catalogKey")}
                ORDER BY t.id
                """,
            )
                .bind("tenantKey", catalog.tenantKey)
                .bindCatalog("catalogKey", catalog)
                .map { rs, _ -> checkNotNull(templateStore.get(handle, app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of(rs.getString("id")), catalog))).template }
                .list()
        }

        return templates.mapNotNull { template ->
            val variants = template.variants
            val default = variants.firstOrNull { it.isDefault } ?: variants.firstOrNull() ?: return@mapNotNull null
            fun modelOf(variant: StoredVariant): TemplateDocument {
                val json = jdbi.withHandle<tools.jackson.databind.JsonNode, Exception> { handle ->
                    objectMapper.valueToTree(checkNotNull(templateStore.model(handle, catalog.tenantKey, variant)) { "Template ${template.key} names a model that is not stored" })
                }
                // Stored relative already.
                return objectMapper.treeToValue(outbound.content(json), TemplateDocument::class.java)
            }
            TemplateResource(
                key = template.key,
                name = template.name,
                themeKey = template.themeKey,
                themeCatalogKey = template.themeCatalogKey.takeIf { template.themeKey != null },
                pdfaEnabled = template.pdfaEnabled,
                dataModel = template.dataModel,
                dataExamples = template.dataExamples,
                templateModel = modelOf(default),
                variants = variants.map { v ->
                    VariantEntry(
                        key = v.key,
                        name = v.title,
                        attributes = v.attributes,
                        templateModel = if (v.key == default.key) null else modelOf(v),
                        isDefault = v.isDefault,
                        description = v.description?.takeIf { it.isNotBlank() },
                    )
                },
                description = template.description,
                metadata = template.metadata?.takeIf { it.isNotEmpty() },
            )
        }
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

        /** [document] without name qualifiers on its references, and nothing else changed. */
        fun withoutNameQualifiers(document: JsonNode): JsonNode = document.deepCopy().also(ResourceReferenceSites::dropNameQualifiers)
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
