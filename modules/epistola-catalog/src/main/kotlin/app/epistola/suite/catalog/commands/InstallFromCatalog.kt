package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.DependencyResolver
import app.epistola.suite.catalog.protocol.AssetResource
import app.epistola.suite.catalog.protocol.AttributeResource
import app.epistola.suite.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.protocol.StencilResource
import app.epistola.suite.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.protocol.ThemeResource
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DataExample
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class InstallFromCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val resourceSlugs: List<String>? = null,
) : Command<List<InstallResult>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
}

data class InstallResult(
    val type: String,
    val slug: String,
    val status: InstallStatus,
    val errorMessage: String? = null,
)

enum class InstallStatus {
    INSTALLED,
    UPDATED,
    SKIPPED,
    FAILED,
}

@Component
class InstallFromCatalogHandler(
    private val jdbi: Jdbi,
    private val catalogClient: CatalogClient,
    private val dependencyResolver: DependencyResolver,
) : CommandHandler<InstallFromCatalog, List<InstallResult>> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: InstallFromCatalog): List<InstallResult> {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${command.catalogKey}")

        val sourceUrl = catalog.sourceUrl
            ?: throw IllegalStateException("Catalog has no source URL: ${command.catalogKey}")

        val manifest = catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)

        val selected = if (command.resourceSlugs != null) {
            manifest.resources.filter { it.slug in command.resourceSlugs }
        } else {
            manifest.resources
        }

        // Resolve dependencies: scan for refs, expand the set (validates completeness)
        val resourcesToInstall = dependencyResolver.resolve(selected, manifest, sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)

        // Install in dependency order: assets → attributes → themes → stencils → templates
        val ordered = resourcesToInstall.sortedBy { INSTALL_ORDER[it.type] ?: 99 }

        return ordered.map { entry ->
            try {
                val detail = catalogClient.fetchResourceDetail(entry.detailUrl, sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)
                val status = installResource(command, detail.resource, manifest.release.version, sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)
                registerCatalogResource(command, entry.type, entry.slug)
                InstallResult(type = entry.type, slug = entry.slug, status = status)
            } catch (e: Exception) {
                logger.error("Failed to install {} '{}' from catalog '{}': {}", entry.type, entry.slug, command.catalogKey, e.message, e)
                InstallResult(type = entry.type, slug = entry.slug, status = InstallStatus.FAILED, errorMessage = e.message)
            }
        }
    }

    private fun installResource(
        command: InstallFromCatalog,
        resource: CatalogResource,
        releaseVersion: String,
        sourceUrl: String,
        authType: app.epistola.suite.catalog.AuthType,
        credential: String?,
    ): InstallStatus = when (resource) {
        is TemplateResource -> installTemplate(command, resource, releaseVersion)
        is ThemeResource -> installTheme(command, resource)
        is StencilResource -> installStencil(command, resource)
        is AttributeResource -> installAttribute(command, resource)
        is AssetResource -> installAsset(command, resource, sourceUrl, authType, credential)
    }

    private fun installTemplate(command: InstallFromCatalog, resource: TemplateResource, releaseVersion: String): InstallStatus {
        val input = ImportTemplateInput(
            slug = resource.slug,
            name = resource.name,
            version = releaseVersion,
            dataModel = resource.dataModel,
            dataExamples = resource.dataExamples?.map {
                DataExample(id = java.util.UUID.randomUUID().toString(), name = it.name, data = it.data)
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
            tenantId = TenantId(command.tenantKey),
            templates = listOf(input),
        ).execute()

        val result = results.first()
        if (result.status == ImportStatus.FAILED) {
            throw RuntimeException("Import failed for '${resource.slug}': ${result.errorMessage}")
        }
        return if (result.status == ImportStatus.CREATED) InstallStatus.INSTALLED else InstallStatus.UPDATED
    }

    private fun installTheme(command: InstallFromCatalog, resource: ThemeResource): InstallStatus {
        val tenantId = TenantId(command.tenantKey)
        return ImportTheme(
            tenantId = tenantId,
            slug = resource.slug,
            name = resource.name,
            description = resource.description,
            documentStyles = resource.documentStyles,
            pageSettings = resource.pageSettings,
            blockStylePresets = resource.blockStylePresets,
            spacingUnit = resource.spacingUnit,
        ).execute()
    }

    private fun installStencil(command: InstallFromCatalog, resource: StencilResource): InstallStatus {
        val tenantId = TenantId(command.tenantKey)
        return ImportStencil(
            tenantId = tenantId,
            slug = resource.slug,
            name = resource.name,
            description = resource.description,
            tags = resource.tags,
            content = resource.content,
        ).execute()
    }

    private fun installAttribute(command: InstallFromCatalog, resource: AttributeResource): InstallStatus {
        val tenantId = TenantId(command.tenantKey)
        return ImportAttribute(
            tenantId = tenantId,
            slug = resource.slug,
            displayName = resource.name,
            allowedValues = resource.allowedValues,
        ).execute()
    }

    private fun installAsset(
        command: InstallFromCatalog,
        resource: AssetResource,
        sourceUrl: String,
        authType: app.epistola.suite.catalog.AuthType,
        credential: String?,
    ): InstallStatus {
        val tenantId = TenantId(command.tenantKey)
        val content = catalogClient.fetchBinaryContent(resource.contentUrl, sourceUrl, authType, credential)

        return ImportAsset(
            tenantId = tenantId,
            id = app.epistola.suite.common.ids.AssetKey.of(resource.slug),
            name = resource.name,
            mediaType = AssetMediaType.fromMimeType(resource.mediaType),
            content = content,
            width = resource.width,
            height = resource.height,
        ).execute()
    }

    private fun registerCatalogResource(command: InstallFromCatalog, resourceType: String, resourceSlug: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_resources (tenant_key, catalog_key, resource_type, resource_slug)
                VALUES (:tenantKey, :catalogKey, :resourceType, :resourceSlug)
                ON CONFLICT (tenant_key, catalog_key, resource_type, resource_slug) DO NOTHING
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("resourceType", resourceType)
                .bind("resourceSlug", resourceSlug)
                .execute()
        }
    }

    companion object {
        private val INSTALL_ORDER = mapOf(
            "asset" to 0,
            "attribute" to 1,
            "theme" to 2,
            "stencil" to 3,
            "template" to 4,
        )
    }
}

/**
 * Thrown when a catalog references dependencies that are not included in the manifest.
 */
class InvalidCatalogException(message: String) : RuntimeException(message)
