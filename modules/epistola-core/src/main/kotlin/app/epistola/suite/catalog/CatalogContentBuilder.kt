package app.epistola.suite.catalog

import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.DataExampleEntry
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.catalog.protocol.FontRef
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.catalog.protocol.VariantEntry
import app.epistola.suite.assets.queries.GetAssetContent
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
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** The canonical content of a catalog, independent of release metadata. */
data class CatalogContent(
    val catalog: CatalogInfo,
    val resourceEntries: List<ResourceEntry>,
    /** `"$type/$slug"` -> detail. */
    val resourceDetails: Map<String, ResourceDetail>,
    val dependencies: List<DependencyRef>?,
    /** Asset content filename (the part after `./resources/asset/`) -> bytes. */
    val assetContents: Map<String, ByteArray>,
) {
    fun toManifest(
        release: ReleaseInfo,
        publisher: PublisherInfo = PublisherInfo(name = "Epistola"),
    ): CatalogManifest = CatalogManifest(
        schemaVersion = CATALOG_MANIFEST_SCHEMA_VERSION,
        catalog = catalog,
        publisher = publisher,
        release = release,
        resources = resourceEntries,
        dependencies = dependencies,
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
) {

    fun build(tenantKey: TenantKey, catalogKey: CatalogKey): CatalogContent {
        val catalog = GetCatalog(tenantKey, catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: $catalogKey")

        val templates = loadTemplates(tenantKey, catalogKey)
        val themes = ExportThemes(tenantKey, catalogKey = catalogKey).query()
        val stencils = ExportStencils(tenantKey, catalogKey = catalogKey).query()
        val attributes = ExportAttributes(tenantKey, catalogKey = catalogKey).query()
        val codeLists = ExportCodeLists(tenantKey, catalogKey = catalogKey).query()
        val fonts = ExportFonts(tenantKey, catalogKey = catalogKey).query()
        val assets = ExportAssets(tenantKey, catalogKey = catalogKey).query()

        val resourceEntries = mutableListOf<ResourceEntry>()
        val resourceDetails = LinkedHashMap<String, ResourceDetail>()

        fun addResource(type: String, slug: String, name: String, description: String?, resource: CatalogResource) {
            resourceEntries.add(
                ResourceEntry(type = type, slug = slug, name = name, description = description, detailUrl = "./resources/$type/$slug.json"),
            )
            // Each part is versioned independently — stamp the detail with its own
            // part's current wire version, not the manifest's (per-part versioning,
            // docs/adr/0007). Unknown types fall back to the manifest version.
            val schemaVersion = CatalogPart.ofResourceType(type)
                ?.let { CATALOG_PART_SCHEMAS.getValue(it).current }
                ?: CATALOG_MANIFEST_SCHEMA_VERSION
            resourceDetails["$type/$slug"] = ResourceDetail(schemaVersion = schemaVersion, resource = resource)
        }

        for (codeList in codeLists) addResource("codeList", codeList.slug, codeList.name, codeList.description, codeList)
        for (font in fonts) addResource("font", font.slug, font.name, null, font)
        for (attr in attributes) addResource("attribute", attr.slug, attr.name, null, attr)
        for (theme in themes) addResource("theme", theme.slug, theme.name, theme.description, theme)
        for (stencil in stencils) addResource("stencil", stencil.slug, stencil.name, stencil.description, stencil)
        for (asset in assets) addResource("asset", asset.slug, asset.name, null, asset)
        for (template in templates) addResource("template", template.slug, template.name, null, template)

        val assetContents = LinkedHashMap<String, ByteArray>()
        for (detail in resourceDetails.values) {
            val resource = detail.resource
            if (resource is AssetResource) {
                val filename = resource.contentUrl.removePrefix("./resources/asset/")
                val uuidStr = filename.substringBefore(".")
                val assetId = try {
                    AssetKey.of(UUID.fromString(uuidStr))
                } catch (_: Exception) {
                    continue
                }
                val content = GetAssetContent(tenantId = tenantKey, assetId = assetId).query() ?: continue
                assetContents[filename] = content.content
            }
        }

        return CatalogContent(
            catalog = CatalogInfo(slug = catalogKey.value, name = catalog.name, description = catalog.description),
            resourceEntries = resourceEntries,
            resourceDetails = resourceDetails,
            dependencies = findCrossCatalogDependencies(templates, attributes, themes, resourceEntries, catalogKey.value),
            assetContents = assetContents,
        )
    }

    private fun loadTemplates(tenantKey: TenantKey, catalogKey: CatalogKey): List<TemplateResource> {
        data class TemplateRow(val id: String, val name: String, val themeKey: String?, val themeCatalogKey: String?, val dataModel: String?, val dataExamples: String?)
        data class VariantRow(val id: String, val title: String?, val attributes: String?, val templateModel: TemplateDocument?, val isDefault: Boolean)

        val templates = jdbi.withHandle<List<TemplateRow>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT dt.id, dt.name, dt.theme_key, dt.theme_catalog_key,
                       cv.data_model::text, cv.data_examples::text
                FROM document_templates dt
                LEFT JOIN LATERAL (
                    SELECT data_model, data_examples FROM contract_versions
                    WHERE tenant_key = dt.tenant_key AND catalog_key = dt.catalog_key AND template_key = dt.id
                    ORDER BY CASE status WHEN 'published' THEN 0 ELSE 1 END, id DESC
                    LIMIT 1
                ) cv ON TRUE
                WHERE dt.tenant_key = :tenantKey AND dt.catalog_key = :catalogKey
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
                        dataModel = rs.getString("data_model"),
                        dataExamples = rs.getString("data_examples"),
                    )
                }
                .list()
        }

        return templates.mapNotNull { template ->
            val variants = jdbi.withHandle<List<VariantRow>, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT v.id, v.title, v.attributes::text, v.is_default, vv.template_model
                    FROM template_variants v
                    LEFT JOIN LATERAL (
                        SELECT template_model FROM template_versions
                        WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND template_key = :templateKey AND variant_key = v.id
                          AND status = 'published'
                        ORDER BY id DESC
                        LIMIT 1
                    ) vv ON TRUE
                    WHERE v.tenant_key = :tenantKey AND v.catalog_key = :catalogKey AND v.template_key = :templateKey
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
                            templateModel = rs.getString("template_model")?.let { objectMapper.readValue(it, TemplateDocument::class.java) },
                            isDefault = rs.getBoolean("is_default"),
                        )
                    }
                    .list()
            }

            val defaultVariant = variants.firstOrNull { it.isDefault } ?: variants.firstOrNull()
                ?: return@mapNotNull null // Skip templates without variants

            val defaultModel = defaultVariant.templateModel ?: return@mapNotNull null

            TemplateResource(
                slug = template.id,
                name = template.name,
                themeId = template.themeKey,
                themeCatalogKey = template.themeCatalogKey,
                dataModel = template.dataModel?.let {
                    objectMapper.readValue<Map<String, Any?>>(
                        it,
                        objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java),
                    )
                },
                dataExamples = template.dataExamples?.let {
                    objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, DataExampleEntry::class.java))
                },
                templateModel = defaultModel,
                variants = variants.map { v ->
                    VariantEntry(
                        id = v.id,
                        title = v.title,
                        attributes = v.attributes?.let { objectMapper.readValue(it, objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, String::class.java)) },
                        templateModel = if (v.id == defaultVariant.id) null else v.templateModel,
                        isDefault = v.isDefault,
                    )
                },
            )
        }
    }

    /**
     * Scan template models for references to resources NOT in this catalog's
     * manifest. Collect them as cross-catalog dependencies. No DB queries.
     */
    private fun findCrossCatalogDependencies(
        templates: List<TemplateResource>,
        attributes: List<AttributeResource>,
        themes: List<ThemeResource>,
        manifestResources: List<ResourceEntry>,
        catalogKey: String,
    ): List<DependencyRef>? {
        val ownResources = manifestResources.map { "${it.type}:${it.slug}" }.toSet()
        val dependencies = mutableSetOf<DependencyRef>()

        fun addFontRef(ref: FontRef) {
            val target = ref.catalogKey ?: return
            if (target == catalogKey) return
            if ("font:${ref.slug}" in ownResources) return
            dependencies.add(DependencyRef.Font(catalogKey = target, slug = ref.slug))
        }
        for (theme in themes) {
            DependencyScanner.themeFontRefs(theme.documentStyles, theme.blockStylePresets).forEach(::addFontRef)
        }

        for (attribute in attributes) {
            val binding = attribute.codeListBinding ?: continue
            val target = binding.catalogKey ?: continue
            if (target == catalogKey) continue
            dependencies.add(DependencyRef.CodeList(catalogKey = target, slug = binding.slug))
        }

        for (template in templates) {
            val themeCatalog = template.themeCatalogKey
            if (template.themeId != null && "theme:${template.themeId}" !in ownResources && themeCatalog != null && themeCatalog != catalogKey) {
                dependencies.add(DependencyRef.Theme(catalogKey = themeCatalog, slug = template.themeId!!))
            }

            val docs = mutableListOf(template.templateModel)
            template.variants.mapNotNull { it.templateModel }.forEach { docs.add(it) }

            for (doc in docs) {
                DependencyScanner.documentFontRefs(doc).forEach(::addFontRef)

                val themeRef = doc.themeRef
                if (themeRef is app.epistola.template.model.ThemeRefOverride) {
                    val refCatalog = themeRef.catalogKey
                    if (refCatalog != null && refCatalog != catalogKey && "theme:${themeRef.themeId}" !in ownResources) {
                        dependencies.add(DependencyRef.Theme(catalogKey = refCatalog, slug = themeRef.themeId))
                    }
                }

                for (node in doc.nodes.values) {
                    when (node.type) {
                        "stencil" -> {
                            val refCatalog = node.props?.get("catalogKey") as? String
                            val stencilId = node.props?.get("stencilId") as? String
                            if (refCatalog != null && stencilId != null && refCatalog != catalogKey && "stencil:$stencilId" !in ownResources) {
                                dependencies.add(DependencyRef.Stencil(catalogKey = refCatalog, slug = stencilId))
                            }
                        }
                        "image" -> {
                            val assetId = node.props?.get("assetId") as? String
                            if (assetId != null && "asset:$assetId" !in ownResources) {
                                dependencies.add(DependencyRef.Asset(slug = assetId))
                            }
                        }
                    }
                }
            }
        }

        return dependencies.toList().ifEmpty { null }
    }
}
