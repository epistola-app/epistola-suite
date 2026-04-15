package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.DependencyScanner
import app.epistola.suite.catalog.protocol.CatalogInfo
import app.epistola.suite.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.protocol.PublisherInfo
import app.epistola.suite.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.protocol.ResourceDetail
import app.epistola.suite.catalog.protocol.ResourceEntry
import app.epistola.suite.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.queries.ExportAssets
import app.epistola.suite.catalog.queries.ExportAttributes
import app.epistola.suite.catalog.queries.ExportStencils
import app.epistola.suite.catalog.queries.ExportThemes
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

/**
 * Exports a self-contained catalog with all resources and their dependencies.
 */
data class ExportCatalog(
    override val tenantKey: TenantKey,
    val catalogSlug: String,
    val catalogName: String,
    val publisherName: String,
    val version: String,
    val templateSlugs: List<String>,
) : Command<ExportCatalogResult>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

data class ExportCatalogResult(
    val manifest: CatalogManifest,
    val resourceDetails: Map<String, ResourceDetail>,
)

@Component
class ExportCatalogHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<ExportCatalog, ExportCatalogResult> {

    override fun handle(command: ExportCatalog): ExportCatalogResult {
        // 1. Load requested templates with their variants and draft content
        val templates = loadTemplates(command.tenantKey, command.templateSlugs)

        // 2. Scan dependencies across all template documents
        val allDeps = templates.map { template ->
            val variantAttributes = template.variants
                .flatMap { it.attributes?.keys ?: emptyList() }
                .toSet()

            val docs = mutableListOf(template.templateModel)
            template.variants.mapNotNull { it.templateModel }.forEach { docs.add(it) }

            docs.map { doc -> DependencyScanner.scan(doc, variantAttributes) }
                .fold(DependencyScanner.Dependencies()) { acc, d -> DependencyScanner.merge(acc, d) }
        }.fold(DependencyScanner.Dependencies()) { acc, d -> DependencyScanner.merge(acc, d) }

        // 3. Export dependent resources
        val themes = if (allDeps.themeRefs.isNotEmpty()) {
            ExportThemes(command.tenantKey, allDeps.themeRefs.toList()).query()
        } else {
            emptyList()
        }

        val attributes = if (allDeps.attributeKeys.isNotEmpty()) {
            ExportAttributes(command.tenantKey, allDeps.attributeKeys.toList()).query()
        } else {
            emptyList()
        }

        val stencils = if (allDeps.stencilRefs.isNotEmpty()) {
            ExportStencils(command.tenantKey, allDeps.stencilRefs.toList()).query()
        } else {
            emptyList()
        }

        val assets = if (allDeps.assetRefs.isNotEmpty()) {
            ExportAssets(command.tenantKey, assetIds = allDeps.assetRefs.toList()).query()
        } else {
            emptyList()
        }

        // 4. Build resource entries and details
        val resourceEntries = mutableListOf<ResourceEntry>()
        val resourceDetails = mutableMapOf<String, ResourceDetail>()

        fun addResource(type: String, slug: String, name: String, description: String?, resource: app.epistola.suite.catalog.protocol.CatalogResource) {
            resourceEntries.add(ResourceEntry(type = type, slug = slug, name = name, description = description, detailUrl = "./resources/$type/$slug.json"))
            resourceDetails["$type/$slug"] = ResourceDetail(schemaVersion = 2, resource = resource)
        }

        for (attr in attributes) addResource("attribute", attr.slug, attr.name, null, attr)
        for (theme in themes) addResource("theme", theme.slug, theme.name, theme.description, theme)
        for (stencil in stencils) addResource("stencil", stencil.slug, stencil.name, stencil.description, stencil)
        for (asset in assets) addResource("asset", asset.slug, asset.name, null, asset)
        for (template in templates) addResource("template", template.slug, template.name, null, template)

        val manifest = CatalogManifest(
            schemaVersion = 2,
            catalog = CatalogInfo(slug = command.catalogSlug, name = command.catalogName),
            publisher = PublisherInfo(name = command.publisherName),
            release = ReleaseInfo(version = command.version),
            resources = resourceEntries,
        )

        return ExportCatalogResult(manifest = manifest, resourceDetails = resourceDetails)
    }

    private fun loadTemplates(tenantKey: TenantKey, slugs: List<String>): List<TemplateResource> {
        data class TemplateRow(
            val id: String,
            val name: String,
            val dataModel: String?,
            val dataExamples: String?,
        )

        data class VariantRow(
            val templateKey: String,
            val id: String,
            val title: String?,
            val attributes: String?,
            val templateModel: TemplateDocument?,
            val isDefault: Boolean,
        )

        val templates = jdbi.withHandle<List<TemplateRow>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, name, data_model::text, data_examples::text
                FROM document_templates
                WHERE tenant_key = :tenantKey AND id IN (<slugs>)
                """,
            )
                .bind("tenantKey", tenantKey)
                .bindList("slugs", slugs)
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
            // Load variants with their latest draft content
            val variants = jdbi.withHandle<List<VariantRow>, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT v.id, v.title, v.attributes::text, v.is_default,
                           vv.template_model
                    FROM template_variants v
                    LEFT JOIN LATERAL (
                        SELECT template_model FROM template_versions
                        WHERE tenant_key = :tenantKey AND template_key = :templateKey AND variant_key = v.id
                        ORDER BY CASE WHEN status = 'published' THEN 0 ELSE 1 END, id DESC
                        LIMIT 1
                    ) vv ON TRUE
                    WHERE v.tenant_key = :tenantKey AND v.template_key = :templateKey
                    """,
                )
                    .bind("tenantKey", tenantKey)
                    .bind("templateKey", template.id)
                    .map { rs, _ ->
                        VariantRow(
                            templateKey = template.id,
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            attributes = rs.getString("attributes"),
                            templateModel = rs.getString("template_model")?.let {
                                objectMapper.readValue(it, TemplateDocument::class.java)
                            },
                            isDefault = rs.getBoolean("is_default"),
                        )
                    }
                    .list()
            }

            val defaultVariant = variants.first { it.isDefault }

            TemplateResource(
                slug = template.id,
                name = template.name,
                dataModel = template.dataModel?.let { objectMapper.readValue(it, ObjectNode::class.java) },
                dataExamples = template.dataExamples?.let {
                    objectMapper.readValue(
                        it,
                        objectMapper.typeFactory.constructCollectionType(
                            List::class.java,
                            app.epistola.suite.catalog.protocol.DataExampleEntry::class.java,
                        ),
                    )
                },
                templateModel = defaultVariant.templateModel
                    ?: throw IllegalStateException("Default variant of template '${template.id}' has no content"),
                variants = variants.map { v ->
                    app.epistola.suite.catalog.protocol.VariantEntry(
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
}
