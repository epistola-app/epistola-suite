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
import app.epistola.suite.assets.queries.ResolveAssetKeysByContentHash
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
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.model.DataExamples
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.templateAtAddress
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

/** Where a catalog's templates, data contracts and stencils are read from. */
enum class ContentSource {
    /** The working copy: what authors edit, and what a release contains. */
    WORKING_COPY,

    /**
     * What each was last published as before 2.0, which the upgrade migration kept in
     * `catalog_upgrade_published`: the content of the 2.0 upgrade's first release. A variant or stencil
     * that was never published is left out, and so is a template whose default variant was not; a
     * template that never published a data contract keeps its working one.
     */
    PUBLISHED_BEFORE_2_0,
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
) {

    fun build(tenantKey: TenantKey, catalogKey: CatalogKey, source: ContentSource = ContentSource.WORKING_COPY): CatalogContent {
        val catalog = GetCatalog(tenantKey, catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: $catalogKey")

        val outbound = Outbound(catalogKey.value, catalog.catalogName, catalogNames(tenantKey))
        val templates = loadTemplates(tenantKey, catalogKey, source, outbound)
        val themes = ExportThemes(tenantKey, catalogKey = catalogKey).query().map { theme ->
            // A theme's own-catalog font references have always travelled as stored; only the others are qualified.
            objectMapper.treeToValue(outbound.qualified(objectMapper.valueToTree(theme)), ThemeResource::class.java)
        }
        val published = if (source == ContentSource.PUBLISHED_BEFORE_2_0) publishedStencils(tenantKey, catalogKey) else null
        val stencils = ExportStencils(tenantKey, catalogKey = catalogKey).query().mapNotNull { exported ->
            val stencil = if (published == null) {
                exported
            } else {
                val version = published[exported.key] ?: return@mapNotNull null
                exported.copy(content = version.first, parameterSchema = version.second)
            }
            stencil.copy(
                content = outbound.content(objectMapper.valueToTree(stencil.content)).let { objectMapper.treeToValue(it, TemplateDocument::class.java) },
            )
        }
        val attributes = ExportAttributes(tenantKey, catalogKey = catalogKey).query().map { attribute ->
            val binding = attribute.codeListBinding ?: return@map attribute
            val (namespace, registry) = outbound.qualifier(binding.catalogKey)
            attribute.copy(codeListBinding = binding.copy(namespace = binding.namespace ?: namespace, registry = binding.registry ?: registry))
        }
        val codeLists = ExportCodeLists(tenantKey, catalogKey = catalogKey).query()
        val fonts = ExportFonts(tenantKey, catalogKey = catalogKey).query()
        val assets = ExportAssets(tenantKey, catalogKey = catalogKey).query()

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
                    val content = GetAssetContent(tenantId = tenantKey, assetId = AssetKey.of(resource.key)).query()
                        ?: continue
                    assetContents[resource.contentPath()] = content.content
                }

                is FontResource -> {
                    // A face carries its own binary from wire v7, so the family's bytes no longer
                    // arrive as separate image resources -- and the face has no wire key to look
                    // its content up by. Resolve by hash: a face uploaded here has the key its
                    // upload generated, one installed from a catalog has a key derived from the
                    // hash, and only the hash names the same bytes in both.
                    val keys = ResolveAssetKeysByContentHash(tenantKey, resource.variants.map { it.contentHash }).query()
                    for (face in resource.variants) {
                        val key = keys[face.contentHash] ?: continue
                        val content = GetAssetContent(tenantId = tenantKey, assetId = key).query() ?: continue
                        assetContents[face.contentPath()] = content.content
                    }
                }

                else -> Unit
            }
        }

        return CatalogContent(
            catalog = catalog.catalogMetadata.toCatalogInfo(
                slug = catalogKey.value,
                name = catalog.name,
                description = catalog.description,
                // The catalog's presentation is catalog-wide, its images belong to a version: only
                // those this version holds are named (any catalog can hold several versions, ADR 0032).
                availableAssetSlugs = assets.mapTo(mutableSetOf()) { it.key },
                namespace = catalog.namespace,
                registry = catalog.registry,
            ).withThemeSettings(tenantKey, catalogKey),
            resourceEntries = resourceEntries,
            resourceDetails = resourceDetails,
            dependencies = findCrossCatalogDependencies(templates, stencils, attributes, themes, resourceEntries, catalogKey.value)
                ?.let { named(tenantKey, it) },
            catalogDependencies = jdbi.withHandle<List<CatalogDependency>, Exception> { handle ->
                dependencyStore.declarations(handle, tenantKey, catalogKey).map { it.toWire() }
            }.ifEmpty { null },
            assetContents = assetContents,
        )
    }

    private fun loadTemplates(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        source: ContentSource,
        outbound: Outbound,
    ): List<TemplateResource> {
        val published = source == ContentSource.PUBLISHED_BEFORE_2_0
        data class TemplateRow(
            val id: String,
            val name: String,
            val themeKey: String?,
            val themeCatalogKey: String?,
            val pdfaEnabled: Boolean,
            val dataModel: String?,
            val dataExamples: String?,
            val description: String?,
        )
        data class VariantRow(val id: String, val title: String?, val attributes: String?, val templateModel: String?, val isDefault: Boolean, val metadata: String?)

        val templates = jdbi.withHandle<List<TemplateRow>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT dt.id, dt.name, dt.description, th.id AS theme_key, thc.catalog_key AS theme_catalog_key, dt.pdfa_enabled,
                       ${if (published) PUBLISHED_CONTRACT_COLUMNS else "dt.data_model::text, dt.data_examples::text"}
                FROM document_templates dt
                ${if (published) PUBLISHED_CONTRACT_JOIN else ""}
                LEFT JOIN themes th ON th.tenant_key = dt.tenant_key AND th.resource_id = dt.theme_resource_id AND ${inCurrentVersion("th")}
                LEFT JOIN catalogs thc ON thc.tenant_key = th.tenant_key AND thc.catalog_id = th.catalog_id
                WHERE dt.tenant_key = :tenantKey AND dt.version_id = ${versionAt("tenantKey", "catalogKey")}
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("catalogKey", catalogKey)
                .map { rs, _ ->
                    TemplateRow(
                        id = rs.getString("id"),
                        name = rs.getString("name"),
                        themeKey = rs.getString("theme_key"),
                        themeCatalogKey = rs.getString("theme_catalog_key"),
                        pdfaEnabled = rs.getBoolean("pdfa_enabled"),
                        dataModel = rs.getString("data_model"),
                        dataExamples = rs.getString("data_examples"),
                        description = rs.getString("description"),
                    )
                }
                .list()
        }

        return templates.mapNotNull { template ->
            val variants = jdbi.withHandle<List<VariantRow>, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT v.id, v.title, v.attributes::text, v.is_default, v.metadata::text,
                           ${if (published) "(pv.payload -> 'templateModel')::text" else "v.template_model::text"} AS template_model
                    FROM template_variants v
                    ${if (published) PUBLISHED_VARIANT_JOIN else ""}
                    WHERE v.tenant_key = :tenantKey AND v.version_id = ${versionAt("tenantKey", "catalogKey")}
                      AND v.template_resource_id = ${templateAtAddress("tenantKey", "catalogKey", "templateKey")}
                    """,
                )
                    .bind("tenantKey", tenantKey)
                    .bind("catalogKey", catalogKey)
                    .bind("templateKey", template.id)
                    .map { rs, _ ->
                        VariantRow(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            attributes = rs.getString("attributes"),
                            templateModel = rs.getString("template_model"),
                            isDefault = rs.getBoolean("is_default"),
                            metadata = rs.getString("metadata"),
                        )
                    }
                    .list()
            }

            val defaultVariant = variants.firstOrNull { it.isDefault }
                // Before 2.0 a template rendered through its default variant, so one whose default was never
                // published is left out of its published content.
                ?: variants.firstOrNull()?.takeUnless { published }
                ?: return@mapNotNull null // Skip templates without variants

            val defaultModel = defaultVariant.templateModel
                ?.let(objectMapper::readTree)
                ?.let(outbound::content)
                ?.let { objectMapper.treeToValue(it, TemplateDocument::class.java) }
                ?: return@mapNotNull null

            val (themeNamespace, themeRegistry) = outbound.qualifier(template.themeCatalogKey)
            TemplateResource(
                key = template.id,
                name = template.name,
                themeKey = template.themeKey,
                themeCatalogKey = template.themeCatalogKey,
                themeNamespace = themeNamespace,
                themeRegistry = themeRegistry,
                pdfaEnabled = template.pdfaEnabled,
                dataModel = template.dataModel?.let {
                    objectMapper.readValue<Map<String, Any?>>(
                        it,
                        objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java),
                    )
                },
                dataExamples = template.dataExamples?.let {
                    objectMapper.readValue(it, DataExamples::class.java).map { example ->
                        DataExampleEntry(name = example.name, data = objectMapper.convertValue(example.data, Map::class.java).mapKeys { (k, _) -> k as String }, key = example.key)
                    }
                },
                templateModel = defaultModel,
                variants = variants.map { v ->
                    VariantEntry(
                        key = v.id,
                        title = v.title,
                        attributes = v.attributes?.let { objectMapper.readValue(it, objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java)) },
                        templateModel = if (v.id == defaultVariant.id) {
                            null
                        } else {
                            v.templateModel
                                ?.let(objectMapper::readTree)
                                ?.let(outbound::content)
                                ?.let { objectMapper.treeToValue(it, TemplateDocument::class.java) }
                        },
                        isDefault = v.isDefault,
                        metadata = v.metadata?.let { objectMapper.readValue(it, VARIANT_METADATA) },
                    )
                },
                description = template.description,
            )
        }
    }

    /** Each stencil's latest published version before 2.0: its content and parameter schema, by stencil key. */
    private fun publishedStencils(tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, Pair<TemplateDocument, Map<String, Any?>?>> = jdbi.withHandle<Map<String, Pair<TemplateDocument, Map<String, Any?>?>>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT s.id, (p.payload -> 'content')::text AS content,
                   NULLIF(p.payload -> 'parameterSchema', 'null'::jsonb)::text AS parameter_schema
            FROM stencils s
            JOIN catalog_upgrade_published p ON p.tenant_key = s.tenant_key AND p.resource_id = s.resource_id AND p.kind = 'stencil'
            WHERE s.tenant_key = :t AND s.version_id = ${versionAt("t", "c")}
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ ->
                rs.getString("id") to (
                    objectMapper.readValue(rs.getString("content"), TemplateDocument::class.java) to
                        rs.getString("parameter_schema")?.let {
                            objectMapper.readValue<Map<String, Any?>>(it, objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java))
                        }
                    )
            }
            .list()
            .toMap()
    }

    /** Every catalog of [tenantKey] by key, which is unique in a tenant: what a stored reference names. */
    private fun catalogNames(tenantKey: TenantKey): Map<String, CatalogName> = jdbi.withHandle<Map<String, CatalogName>, Exception> { handle ->
        handle.createQuery("SELECT catalog_key, registry, namespace FROM catalogs WHERE tenant_key = :t")
            .bind("t", tenantKey)
            .map { rs, _ -> rs.getString("catalog_key").let { it to CatalogName(rs.getString("registry"), rs.getString("namespace"), CatalogKey.of(it)) } }
            .list()
            .toMap()
    }

    /**
     * How the references of catalog [catalogKey] (named [owner]) travel: stored ones are absolute
     * so relocation cannot change their meaning; exported ones are relative to their own catalog,
     * so the ZIP stays installable under a different key, and name the namespace and registry of
     * a catalog in another one (contract ADR 0003), since on the wire a key alone means one in the
     * owner's namespace. [names] is the tenant's catalogs by key.
     */
    class Outbound(private val catalogKey: String, private val owner: CatalogName, private val names: Map<String, CatalogName>) {
        /** [document] (template, stencil or theme JSON) with its references as they travel. */
        fun content(document: JsonNode): JsonNode = document.deepCopy().also { root ->
            ResourceReferenceSites.relativizeOwnCatalog(root, catalogKey)
            ResourceReferenceSites.qualifyOtherCatalogs(root, owner, names::get)
        }

        /** [document] with its references into catalogs of another name qualified, and nothing else changed. */
        fun qualified(document: JsonNode): JsonNode = document.deepCopy().also { root ->
            ResourceReferenceSites.qualifyOtherCatalogs(root, owner, names::get)
        }

        /**
         * The namespace and registry a relational reference into catalog [target] names (a
         * template's theme, a code-list binding): each null when it is the owner's, and both for
         * no catalog, the owner's own or one the tenant does not have.
         */
        fun qualifier(target: String?): Pair<String?, String?> {
            val name = target?.let(names::get) ?: return null to null
            if (name.namespace == owner.namespace && name.registry == owner.registry) return null to null
            return name.namespace to name.registry.takeIf { it != owner.registry }
        }
    }

    /**
     * [dependencies] each with the registry and namespace of the catalog it names (contract ADR
     * 0003): a key alone does not say which catalog is meant. A dependency on a catalog the tenant
     * does not have stays unnamed, and the validator refuses it, as it should.
     */
    private fun named(tenantKey: TenantKey, dependencies: List<DependencyRef>): List<DependencyRef> {
        val names = jdbi.withHandle<Map<String, Pair<String, String>>, Exception> { handle ->
            handle.createQuery("SELECT catalog_key, registry, namespace FROM catalogs WHERE tenant_key = :t AND catalog_key IN (<keys>)")
                .bind("t", tenantKey)
                .bindList("keys", dependencies.map { it.catalogKey }.distinct())
                .map { rs, _ -> rs.getString("catalog_key") to (rs.getString("registry") to rs.getString("namespace")) }
                .list()
                .toMap()
        }
        return dependencies.map { dependency ->
            val (registry, namespace) = names[dependency.catalogKey] ?: return@map dependency
            when (dependency) {
                is DependencyRef.Theme -> dependency.copy(registry = registry, namespace = namespace)
                is DependencyRef.Stencil -> dependency.copy(registry = registry, namespace = namespace)
                is DependencyRef.Image -> dependency.copy(registry = registry, namespace = namespace)
                is DependencyRef.CodeList -> dependency.copy(registry = registry, namespace = namespace)
                is DependencyRef.Font -> dependency.copy(registry = registry, namespace = namespace)
            }
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

/** The template's latest published data contract before 2.0, or its working one when it published none. */
private const val PUBLISHED_CONTRACT_COLUMNS =
    "CASE WHEN pc.payload IS NULL THEN dt.data_model ELSE NULLIF(pc.payload -> 'dataModel', 'null'::jsonb) END::text AS data_model, " +
        "CASE WHEN pc.payload IS NULL THEN dt.data_examples ELSE pc.payload -> 'dataExamples' END::text AS data_examples"

private const val PUBLISHED_CONTRACT_JOIN =
    "LEFT JOIN catalog_upgrade_published pc ON pc.tenant_key = dt.tenant_key AND pc.resource_id = dt.resource_id AND pc.kind = 'contract'"

/** A variant's latest published model before 2.0; a variant that never published one is left out. */
private const val PUBLISHED_VARIANT_JOIN =
    "JOIN catalog_upgrade_published pv ON pv.tenant_key = v.tenant_key AND pv.resource_id = v.template_resource_id " +
        "AND pv.kind = 'variant' AND pv.part = v.id"

/**
 * The catalog details with the version's theme settings (contract ADR 0007): its default theme,
 * qualified when it lives in another catalog, and the presets it declares. Absent ones leave the
 * details, and so the fingerprint, as they were.
 */
private fun CatalogInfo.withThemeSettings(tenantKey: TenantKey, catalogKey: CatalogKey): CatalogInfo {
    val settings = app.epistola.suite.catalog.versions.GetCatalogThemeSettings(tenantKey, catalogKey).query()
    if (settings.defaultTheme == null && settings.presets.isEmpty()) return this
    val theme = settings.defaultTheme?.let { theme ->
        if (theme.catalogKey == catalogKey) {
            app.epistola.catalog.protocol.CatalogThemeRef(key = theme.themeKey.value)
        } else {
            val other = GetCatalog(tenantKey, theme.catalogKey).query()
            app.epistola.catalog.protocol.CatalogThemeRef(
                key = theme.themeKey.value,
                catalogKey = theme.catalogKey.value,
                namespace = other?.namespace,
                registry = other?.registry,
            )
        }
    }
    return copyWithMetadata(
        defaultTheme = theme,
        presets = settings.presets.map { app.epistola.catalog.protocol.CatalogPresetDeclaration(it.key, it.name, it.description) },
    )
}

/** A variant's stored `metadata` as the protocol models it ([VariantEntry.metadata]). */
private val VARIANT_METADATA = object : tools.jackson.core.type.TypeReference<Map<String, Map<String, Any?>>>() {}
