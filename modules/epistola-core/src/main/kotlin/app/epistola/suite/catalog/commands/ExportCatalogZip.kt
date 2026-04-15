package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.protocol.AssetResource
import app.epistola.suite.catalog.protocol.CatalogInfo
import app.epistola.suite.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.protocol.PublisherInfo
import app.epistola.suite.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.protocol.ResourceDetail
import app.epistola.suite.catalog.protocol.ResourceEntry
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
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports all resources in a catalog as a self-contained ZIP archive.
 *
 * Unlike [ExportCatalog] which only exports template dependencies,
 * this exports ALL resources in the catalog by `catalog_key`.
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
) : CommandHandler<ExportCatalogZip, ExportCatalogZipResult> {

    override fun handle(command: ExportCatalogZip): ExportCatalogZipResult {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${command.catalogKey}")

        val version = LocalDate.now().toString()

        // Query ALL resources in this catalog (not just template dependencies)
        val templateSlugs = listTemplateSlugs(command.tenantKey, command.catalogKey)
        val templates = if (templateSlugs.isNotEmpty()) {
            // Reuse ExportCatalog for templates since it has the complex variant/version loading
            ExportCatalog(
                tenantKey = command.tenantKey,
                catalogKey = command.catalogKey,
                catalogSlug = command.catalogKey.value,
                catalogName = catalog.name,
                publisherName = "Epistola",
                version = version,
                templateSlugs = templateSlugs,
            ).execute()
        } else {
            null
        }

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

        // Add non-template resources from direct catalog queries
        for (attr in attributes) addResource("attribute", attr.slug, attr.name, null, attr)
        for (theme in themes) addResource("theme", theme.slug, theme.name, theme.description, theme)
        for (stencil in stencils) addResource("stencil", stencil.slug, stencil.name, stencil.description, stencil)
        for (asset in assets) addResource("asset", asset.slug, asset.name, null, asset)

        // Add templates from ExportCatalog result (which has the full variant/version structure)
        if (templates != null) {
            for ((key, detail) in templates.resourceDetails) {
                if (key.startsWith("template/")) {
                    val resource = detail.resource
                    resourceEntries.add(ResourceEntry(type = "template", slug = resource.slug, name = resource.name, description = null, detailUrl = "./resources/$key.json"))
                    resourceDetails[key] = detail
                }
            }
        }

        val manifest = CatalogManifest(
            schemaVersion = 2,
            catalog = CatalogInfo(slug = command.catalogKey.value, name = catalog.name, description = catalog.description),
            publisher = PublisherInfo(name = "Epistola"),
            release = ReleaseInfo(version = version),
            resources = resourceEntries,
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
                    // contentUrl is "./resources/assets/{uuid}.{ext}" — extract UUID
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

        val filename = "${command.catalogKey.value}-$version.zip"
        return ExportCatalogZipResult(zipBytes = baos.toByteArray(), filename = filename)
    }

    private fun listTemplateSlugs(tenantKey: TenantKey, catalogKey: CatalogKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery(
            "SELECT id FROM document_templates WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
        )
            .bind("tenantKey", tenantKey)
            .bind("catalogKey", catalogKey)
            .mapTo(String::class.java)
            .list()
    }
}
