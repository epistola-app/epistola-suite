package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.CatalogType
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
 * Exports all resources in an authored catalog as a self-contained ZIP archive.
 *
 * The ZIP structure matches the catalog protocol layout:
 * ```
 * catalog.json
 * resources/
 *   template/slug.json
 *   theme/slug.json
 *   stencil/slug.json
 *   attribute/slug.json
 *   asset/slug.json        (metadata)
 *   assets/uuid             (binary content)
 * ```
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

        require(catalog.type == CatalogType.AUTHORED) {
            "Only authored catalogs can be exported"
        }

        // List all template slugs in this catalog
        val templateSlugs = listTemplateSlugs(command.tenantKey, command.catalogKey)

        val version = LocalDate.now().toString()

        // Use ExportCatalog to build the manifest and resource details
        val exportResult = ExportCatalog(
            tenantKey = command.tenantKey,
            catalogSlug = command.catalogKey.value,
            catalogName = catalog.name,
            publisherName = "Epistola",
            version = version,
            templateSlugs = templateSlugs,
        ).execute()

        // Build the ZIP
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            // catalog.json
            zip.putNextEntry(ZipEntry("catalog.json"))
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(exportResult.manifest))
            zip.closeEntry()

            // Resource detail files
            for ((key, detail) in exportResult.resourceDetails) {
                zip.putNextEntry(ZipEntry("resources/$key.json"))
                zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(detail))
                zip.closeEntry()
            }

            // Asset binary content
            for ((key, detail) in exportResult.resourceDetails) {
                val resource = detail.resource
                if (resource is app.epistola.suite.catalog.protocol.AssetResource) {
                    val assetId = try {
                        AssetKey.of(UUID.fromString(resource.contentUrl.removePrefix("./resources/assets/")))
                    } catch (_: Exception) {
                        continue
                    }
                    val content = GetAssetContent(
                        tenantId = command.tenantKey,
                        assetId = assetId,
                    ).query() ?: continue

                    zip.putNextEntry(ZipEntry("resources/assets/${assetId.value}"))
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
