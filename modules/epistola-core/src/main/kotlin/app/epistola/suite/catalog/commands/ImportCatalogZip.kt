package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.RESOURCE_INSTALL_ORDER
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DataExample
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Imports a catalog from a ZIP archive.
 *
 * - If the catalog slug already exists and is AUTHORED: updates resources in place
 * - If the catalog slug already exists and is SUBSCRIBED: rejects the import
 * - If the catalog slug is new: creates the catalog with the chosen type
 */
data class ImportCatalogZip(
    override val tenantKey: TenantKey,
    val zipBytes: ByteArray,
    val catalogType: CatalogType,
) : Command<ImportCatalogZipResult>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
}

data class ImportCatalogZipResult(
    val catalogKey: CatalogKey,
    val catalogName: String,
    val results: List<InstallResult>,
)

@Component
class ImportCatalogZipHandler(
    private val objectMapper: ObjectMapper,
    private val protocolMapper: ProtocolMapper,
    private val sizeLimits: app.epistola.suite.catalog.CatalogSizeLimits,
    private val jdbi: org.jdbi.v3.core.Jdbi,
) : CommandHandler<ImportCatalogZip, ImportCatalogZipResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ImportCatalogZip): ImportCatalogZipResult = CatalogImportContext.runAsImport {
        // Size checks: compressed ZIP and decompressed total
        val maxZipSize = sizeLimits.maxZipSize.toBytes()
        val maxDecompressedSize = sizeLimits.maxDecompressedSize.toBytes()

        require(command.zipBytes.size <= maxZipSize) {
            "Catalog ZIP exceeds maximum size of ${sizeLimits.maxZipSize} " +
                "(actual: ${command.zipBytes.size / 1024 / 1024} MB)"
        }

        // Extract ZIP contents into memory with decompression limit
        val entries = mutableMapOf<String, ByteArray>()
        var totalDecompressed = 0L
        ZipInputStream(ByteArrayInputStream(command.zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zip.readAllBytes()
                    totalDecompressed += bytes.size
                    require(totalDecompressed <= maxDecompressedSize) {
                        "Catalog ZIP decompressed content exceeds maximum size of ${sizeLimits.maxDecompressedSize}"
                    }
                    entries[entry.name] = bytes
                }
                entry = zip.nextEntry
            }
        }

        // Parse manifest
        val manifestBytes = entries["catalog.json"]
            ?: throw IllegalArgumentException("ZIP does not contain catalog.json")
        val manifest = objectMapper.readValue(manifestBytes, CatalogManifest::class.java)
        val catalogKey = CatalogKey.of(manifest.catalog.slug)

        // Check if catalog already exists
        val existingCatalog = GetCatalog(command.tenantKey, catalogKey).query()
        if (existingCatalog != null && existingCatalog.type == CatalogType.SUBSCRIBED) {
            throw IllegalArgumentException(
                "Catalog '${catalogKey.value}' is subscribed and cannot be updated from a ZIP. " +
                    "Only authored catalogs can be updated.",
            )
        }

        // Validate cross-catalog dependencies exist (batch check)
        val dependencies = manifest.dependencies.orEmpty()
        if (dependencies.isNotEmpty()) {
            val missing = findMissingDependencies(command.tenantKey, dependencies)
            if (missing.isNotEmpty()) {
                val details = missing.joinToString(", ") { dep ->
                    when (dep) {
                        is app.epistola.catalog.protocol.DependencyRef.Theme -> "theme '${dep.slug}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.Stencil -> "stencil '${dep.slug}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.Asset -> "asset '${dep.slug}'"
                    }
                }
                throw IllegalArgumentException(
                    "Catalog '${catalogKey.value}' has unmet dependencies: $details. " +
                        "Import or create these resources first.",
                )
            }
        }

        // Create catalog if it doesn't exist
        if (existingCatalog == null) {
            CreateCatalog(
                tenantKey = command.tenantKey,
                id = catalogKey,
                name = manifest.catalog.name,
                description = manifest.catalog.description,
            ).execute()
        }

        // Install resources in dependency order
        val ordered = manifest.resources.sortedBy { RESOURCE_INSTALL_ORDER[it.type] ?: 99 }
        val tenantId = TenantId(command.tenantKey)

        val results = ordered.map { entry ->
            try {
                val detailPath = entry.detailUrl.removePrefix("./")
                val detailBytes = entries[detailPath]
                    ?: throw IllegalArgumentException("Missing resource detail: ${entry.detailUrl}")
                val detail = objectMapper.readValue(detailBytes, ResourceDetail::class.java)

                val status = installResource(
                    tenantKey = command.tenantKey,
                    tenantId = tenantId,
                    catalogKey = catalogKey,
                    resource = detail.resource,
                    version = manifest.release.version,
                    entries = entries,
                )
                InstallResult(type = entry.type, slug = entry.slug, status = status)
            } catch (e: Exception) {
                logger.error("Failed to import {} '{}': {}", entry.type, entry.slug, e.message, e)
                InstallResult(type = entry.type, slug = entry.slug, status = InstallStatus.FAILED, errorMessage = e.message)
            }
        }

        ImportCatalogZipResult(
            catalogKey = catalogKey,
            catalogName = manifest.catalog.name,
            results = results,
        )
    }

    private fun installResource(
        tenantKey: TenantKey,
        tenantId: TenantId,
        catalogKey: CatalogKey,
        resource: CatalogResource,
        version: String,
        entries: Map<String, ByteArray>,
    ): InstallStatus = when (resource) {
        is TemplateResource -> {
            val input = ImportTemplateInput(
                slug = resource.slug,
                name = resource.name,
                version = version,
                dataModel = protocolMapper.toObjectNode(resource.dataModel),
                dataExamples = resource.dataExamples?.map {
                    DataExample(id = java.util.UUID.randomUUID().toString(), name = it.name, data = protocolMapper.toObjectNode(it.data)!!)
                } ?: emptyList(),
                templateModel = resource.templateModel,
                variants = resource.variants.map { variant ->
                    ImportVariantInput(
                        id = variant.id,
                        title = variant.title,
                        attributes = variant.attributes ?: emptyMap(),
                        templateModel = variant.templateModel,
                        isDefault = variant.isDefault,
                    )
                },
                publishTo = emptyList(),
            )
            val results = ImportTemplates(
                tenantId = tenantId,
                catalogKey = catalogKey,
                templates = listOf(input),
            ).execute()
            val result = results.first()
            if (result.status == ImportStatus.FAILED) {
                throw RuntimeException("Import failed for '${resource.slug}': ${result.errorMessage}")
            }
            if (result.status == ImportStatus.CREATED) InstallStatus.INSTALLED else InstallStatus.UPDATED
        }

        is ThemeResource -> ImportTheme(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.slug,
            name = resource.name,
            description = resource.description,
            documentStyles = protocolMapper.mapToDocumentStyles(resource.documentStyles),
            pageSettings = resource.pageSettings,
            blockStylePresets = protocolMapper.mapToBlockStylePresets(resource.blockStylePresets),
            spacingUnit = resource.spacingUnit,
        ).execute()

        is StencilResource -> ImportStencil(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.slug,
            name = resource.name,
            description = resource.description,
            tags = resource.tags ?: emptyList(),
            content = resource.content,
        ).execute()

        is AttributeResource -> ImportAttribute(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.slug,
            displayName = resource.name,
            allowedValues = resource.allowedValues ?: emptyList(),
        ).execute()

        is AssetResource -> {
            // Resolve binary content from ZIP entries
            val contentPath = resource.contentUrl.removePrefix("./")
            val contentBytes = entries[contentPath]
                ?: throw IllegalArgumentException("Missing asset content: ${resource.contentUrl}")
            val mediaType = AssetMediaType.fromMimeType(resource.mediaType)
            // Asset slug is the UUID string
            val assetId = AssetKey.of(java.util.UUID.fromString(resource.slug))
            ImportAsset(
                tenantId = tenantId,
                catalogKey = catalogKey,
                id = assetId,
                name = resource.name,
                mediaType = mediaType,
                content = contentBytes,
                width = resource.width,
                height = resource.height,
            ).execute()
        }
    }

    /**
     * Checks all dependencies in a single query per resource type.
     * Returns the list of dependencies that do NOT exist in the target system.
     */
    private fun findMissingDependencies(
        tenantKey: TenantKey,
        deps: List<app.epistola.catalog.protocol.DependencyRef>,
    ): List<app.epistola.catalog.protocol.DependencyRef> {
        if (deps.isEmpty()) return emptyList()

        val found = mutableSetOf<String>() // unique key per dependency

        jdbi.withHandle<Unit, Exception> { handle ->
            // Catalog-scoped: themes
            val themeDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Theme>()
            if (themeDeps.isNotEmpty()) {
                handle.createQuery("SELECT catalog_key, id FROM themes WHERE tenant_key = :tenantKey AND id IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", themeDeps.map { it.slug })
                    .map { rs, _ -> "theme:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: stencils
            val stencilDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Stencil>()
            if (stencilDeps.isNotEmpty()) {
                handle.createQuery("SELECT catalog_key, id FROM stencils WHERE tenant_key = :tenantKey AND id IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", stencilDeps.map { it.slug })
                    .map { rs, _ -> "stencil:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Tenant-global: assets
            val assetDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Asset>()
            if (assetDeps.isNotEmpty()) {
                handle.createQuery("SELECT id::text FROM assets WHERE tenant_key = :tenantKey AND id::text IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", assetDeps.map { it.slug })
                    .mapTo(String::class.java)
                    .list()
                    .forEach { found.add("asset:$it") }
            }
        }

        return deps.filter { dep ->
            when (dep) {
                is app.epistola.catalog.protocol.DependencyRef.Theme -> "theme:${dep.catalogKey}:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Stencil -> "stencil:${dep.catalogKey}:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Asset -> "asset:${dep.slug}" !in found
            }
        }
    }
}
