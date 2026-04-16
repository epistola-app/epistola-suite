package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.protocol.AssetResource
import app.epistola.suite.catalog.protocol.CatalogInfo
import app.epistola.suite.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.protocol.DataExampleEntry
import app.epistola.suite.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.protocol.PublisherInfo
import app.epistola.suite.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.protocol.ResourceDetail
import app.epistola.suite.catalog.protocol.ResourceEntry
import app.epistola.suite.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.protocol.VariantEntry
import app.epistola.suite.catalog.queries.ExportAssets
import app.epistola.suite.catalog.queries.ExportAttributes
import app.epistola.suite.catalog.queries.ExportStencils
import app.epistola.suite.catalog.queries.ExportThemes
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports all resources in a catalog as a self-contained ZIP archive.
 * Queries all resources by `catalog_key` and includes asset binary content.
 */
data class ExportCatalogZip(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<ExportCatalogZipResult>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

data class ExportCatalogZipResult(
    val zipBytes: ByteArray,
    val filename: String,
)

@Component
class ExportCatalogZipHandler(
    private val objectMapper: ObjectMapper,
    private val jdbi: Jdbi,
    private val sizeLimits: CatalogSizeLimits,
) : CommandHandler<ExportCatalogZip, ExportCatalogZipResult> {

    override fun handle(command: ExportCatalogZip): ExportCatalogZipResult {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${command.catalogKey}")

        val version = LocalDate.now().toString()

        // Query ALL resources in this catalog
        val templates = loadTemplates(command.tenantKey, command.catalogKey)
        val themes = ExportThemes(command.tenantKey, catalogKey = command.catalogKey).query()
        val stencils = ExportStencils(command.tenantKey, catalogKey = command.catalogKey).query()
        val attributes = ExportAttributes(command.tenantKey, catalogKey = command.catalogKey).query()
        val assets = ExportAssets(command.tenantKey, catalogKey = command.catalogKey).query()

        // Build resource entries and details
        val resourceEntries = mutableListOf<ResourceEntry>()
        val resourceDetails = mutableMapOf<String, ResourceDetail>()

        fun addResource(type: String, slug: String, name: String, description: String?, resource: CatalogResource) {
            resourceEntries.add(ResourceEntry(type = type, slug = slug, name = name, description = description, detailUrl = "./resources/$type/$slug.json"))
            resourceDetails["$type/$slug"] = ResourceDetail(schemaVersion = 2, resource = resource)
        }

        for (attr in attributes) addResource("attribute", attr.slug, attr.name, null, attr)
        for (theme in themes) addResource("theme", theme.slug, theme.name, theme.description, theme)
        for (stencil in stencils) addResource("stencil", stencil.slug, stencil.name, stencil.description, stencil)
        for (asset in assets) addResource("asset", asset.slug, asset.name, null, asset)
        for (template in templates) addResource("template", template.slug, template.name, null, template)

        // Build manifest first (without dependencies)
        val manifest = CatalogManifest(
            schemaVersion = 2,
            catalog = CatalogInfo(slug = command.catalogKey.value, name = catalog.name, description = catalog.description),
            publisher = PublisherInfo(name = "Epistola"),
            release = ReleaseInfo(version = version),
            resources = resourceEntries,
            dependencies = findCrossCatalogDependencies(templates, resourceEntries, command.catalogKey.value),
        )

        // Build the ZIP
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("catalog.json"))
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest))
            zip.closeEntry()

            for ((key, detail) in resourceDetails) {
                zip.putNextEntry(ZipEntry("resources/$key.json"))
                zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(detail))
                zip.closeEntry()
            }

            // Asset binary content
            for ((_, detail) in resourceDetails) {
                val resource = detail.resource
                if (resource is AssetResource) {
                    val filename = resource.contentUrl.removePrefix("./resources/assets/")
                    val uuidStr = filename.substringBefore(".")
                    val assetId = try {
                        AssetKey.of(UUID.fromString(uuidStr))
                    } catch (_: Exception) {
                        continue
                    }
                    val content = GetAssetContent(
                        tenantId = command.tenantKey,
                        assetId = assetId,
                    ).query() ?: continue

                    zip.putNextEntry(ZipEntry("resources/assets/$filename"))
                    zip.write(content.content)
                    zip.closeEntry()
                }
            }
        }

        val zipBytes = baos.toByteArray()
        require(zipBytes.size <= sizeLimits.maxDecompressedSize.toBytes()) {
            "Catalog export exceeds maximum size of ${sizeLimits.maxDecompressedSize} " +
                "(actual: ${zipBytes.size / 1024 / 1024} MB)"
        }

        val filename = "${command.catalogKey.value}-$version.zip"
        return ExportCatalogZipResult(zipBytes = zipBytes, filename = filename)
    }

    private fun loadTemplates(tenantKey: TenantKey, catalogKey: CatalogKey): List<TemplateResource> {
        data class TemplateRow(val id: String, val name: String, val dataModel: String?, val dataExamples: String?)
        data class VariantRow(val id: String, val title: String?, val attributes: String?, val templateModel: TemplateDocument?, val isDefault: Boolean)

        val templates = jdbi.withHandle<List<TemplateRow>, Exception> { handle ->
            handle.createQuery(
                "SELECT id, name, data_model::text, data_examples::text FROM document_templates WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
            )
                .bind("tenantKey", tenantKey)
                .bind("catalogKey", catalogKey)
                .map { rs, _ ->
                    TemplateRow(
                        id = rs.getString("id"),
                        name = rs.getString("name"),
                        dataModel = rs.getString("data_model"),
                        dataExamples = rs.getString("data_examples"),
                    )
                }
                .list()
        }

        return templates.map { template ->
            val variants = jdbi.withHandle<List<VariantRow>, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT v.id, v.title, v.attributes::text, v.is_default, vv.template_model
                    FROM template_variants v
                    LEFT JOIN LATERAL (
                        SELECT template_model FROM template_versions
                        WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND template_key = :templateKey AND variant_key = v.id
                        ORDER BY CASE WHEN status = 'published' THEN 0 ELSE 1 END, id DESC
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
                ?: throw IllegalStateException("Template '${template.id}' has no variants")

            TemplateResource(
                slug = template.id,
                name = template.name,
                dataModel = template.dataModel?.let { objectMapper.readValue(it, ObjectNode::class.java) },
                dataExamples = template.dataExamples?.let {
                    objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, DataExampleEntry::class.java))
                },
                templateModel = defaultVariant.templateModel
                    ?: throw IllegalStateException("Default variant of template '${template.id}' has no content"),
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
     * Scan template models for references to resources NOT in this catalog's manifest.
     * Collect them as cross-catalog dependencies. No DB queries — purely in-memory.
     */
    private fun findCrossCatalogDependencies(
        templates: List<TemplateResource>,
        manifestResources: List<ResourceEntry>,
        catalogKey: String,
    ): List<DependencyRef>? {
        // Build a set of all resources included in this catalog
        val ownResources = manifestResources.map { "${it.type}:${it.slug}" }.toSet()

        val dependencies = mutableSetOf<DependencyRef>()

        for (template in templates) {
            val docs = mutableListOf(template.templateModel)
            template.variants.mapNotNull { it.templateModel }.forEach { docs.add(it) }

            for (doc in docs) {
                // Theme references
                val themeRef = doc.themeRef
                if (themeRef is app.epistola.template.model.ThemeRefOverride) {
                    val refCatalog = themeRef.catalogKey
                    if (refCatalog != null && refCatalog != catalogKey && "theme:${themeRef.themeId}" !in ownResources) {
                        dependencies.add(DependencyRef.Theme(catalogKey = refCatalog, slug = themeRef.themeId))
                    }
                }

                // Node references
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
                                // Asset is external — we don't know which catalog it's in from the template model alone,
                                // but we know it's not in this catalog. Use the current catalog as placeholder.
                                // The importing system will resolve it by tenant-global asset lookup.
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
