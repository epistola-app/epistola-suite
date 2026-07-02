package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.DependencyResolver
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.RESOURCE_INSTALL_ORDER
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
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
    RequiresPermission,
    // Downloads catalog content over HTTP mid-command.
    SelfManagedTransaction {
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
    private val protocolMapper: ProtocolMapper,
) : CommandHandler<InstallFromCatalog, List<InstallResult>> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: InstallFromCatalog): List<InstallResult> = CatalogImportContext.runAsImport {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${command.catalogKey}")

        val sourceUrl = catalog.sourceUrl
            ?: throw IllegalStateException("Catalog has no source URL: ${command.catalogKey}")

        val migratedManifest = catalogClient.fetchMigratedManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)
        val manifest = migratedManifest.manifest
        val catalogCtx = migratedManifest.catalog

        val selected = if (command.resourceSlugs != null) {
            manifest.resources.filter { it.slug in command.resourceSlugs }
        } else {
            manifest.resources
        }

        // Resolve dependencies: scan for refs, expand the set (validates completeness)
        val resourcesToInstall = dependencyResolver.resolve(selected, manifest, sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value, catalogCtx)

        // Install in dependency order: assets → attributes → themes → stencils → templates
        val ordered = resourcesToInstall.sortedBy { RESOURCE_INSTALL_ORDER[it.type] ?: 99 }

        ordered.map { entry ->
            try {
                val detail = catalogClient.fetchResourceDetail(entry.type, entry.detailUrl, sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value, catalogCtx)
                val status = installResource(command, detail.resource, manifest.release.version, sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)
                InstallResult(type = entry.type, slug = entry.slug, status = status)
            } catch (e: CatalogSchemaException) {
                // A wire-version gate failure (too new/old/unknown) rejects the
                // whole install and surfaces the dedicated operator remediation
                // (UI fragment / RFC 9457 problem) — never downgraded to a single
                // FAILED resource by the generic catch below. Matches the manifest
                // gate (outside this loop) and the ZIP path in ImportCatalogZip.
                throw e
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
        is CodeListResource -> installCodeList(command, resource)
        is FontResource -> installFont(command, resource)
    }

    private fun installTemplate(command: InstallFromCatalog, resource: TemplateResource, releaseVersion: String): InstallStatus {
        val input = ImportTemplateInput(
            slug = resource.slug,
            name = resource.name,
            version = releaseVersion,
            themeId = resource.themeId,
            themeCatalogKey = if (resource.themeId != null) resource.themeCatalogKey ?: command.catalogKey.value else null,
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
            tenantId = TenantId(command.tenantKey),
            catalogKey = command.catalogKey,
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
            catalogKey = command.catalogKey,
            slug = resource.slug,
            name = resource.name,
            description = resource.description,
            documentStyles = protocolMapper.mapToDocumentStyles(resource.documentStyles),
            pageSettings = resource.pageSettings,
            blockStylePresets = protocolMapper.mapToBlockStylePresets(resource.blockStylePresets),
            spacingUnit = resource.spacingUnit,
        ).execute()
    }

    private fun installStencil(command: InstallFromCatalog, resource: StencilResource): InstallStatus {
        val tenantId = TenantId(command.tenantKey)
        // `InstallFromCatalog` installs one resource without orchestrating cross-
        // resource renumber rewrites, so it cannot wire RENUMBER into templates.
        // Surface FAIL conflicts as-is; the operator must use the multi-resource
        // ZIP import path to renumber.
        return ImportStencil(
            tenantId = tenantId,
            catalogKey = command.catalogKey,
            slug = resource.slug,
            name = resource.name,
            version = resource.version,
            description = resource.description,
            tags = resource.tags,
            content = resource.content,
            parameterSchema = resource.parameterSchema,
        ).execute().status
    }

    private fun installAttribute(command: InstallFromCatalog, resource: AttributeResource): InstallStatus {
        val tenantId = TenantId(command.tenantKey)
        // A binding's `catalogKey` defaults to the attribute's own catalog —
        // the typical case is a catalog that authors both the attribute and
        // the list it binds to. Cross-catalog bindings (e.g. an attribute in
        // `default` binding to a list in `system`) carry an explicit
        // catalogKey on the wire.
        val bindingCatalog = resource.codeListBinding?.let { binding ->
            binding.catalogKey?.let(CatalogKey::of) ?: command.catalogKey
        }
        val bindingSlug = resource.codeListBinding?.slug?.let(CodeListKey::of)
        return ImportAttribute(
            tenantId = tenantId,
            catalogKey = command.catalogKey,
            slug = resource.slug,
            displayName = resource.name,
            allowedValues = resource.allowedValues,
            codeListCatalogKey = bindingCatalog,
            codeListSlug = bindingSlug,
        ).execute()
    }

    private fun installCodeList(command: InstallFromCatalog, resource: CodeListResource): InstallStatus {
        val tenantId = TenantId(command.tenantKey)
        return ImportCodeList(
            tenantId = tenantId,
            catalogKey = command.catalogKey,
            slug = resource.slug,
            displayName = resource.name,
            description = resource.description,
            entries = resource.entries.map { wire ->
                CodeListEntry(
                    code = wire.code,
                    label = wire.label,
                    sortOrder = wire.sortOrder,
                    hidden = wire.hidden,
                )
            },
        ).execute()
    }

    private fun installFont(command: InstallFromCatalog, resource: FontResource): InstallStatus {
        val tenantId = TenantId(command.tenantKey)
        // Each variant's binary rode the catalog as an `AssetResource` already
        // imported in this same catalog, so every variant is ASSET-backed and
        // the asset slug is the asset's UUID. System (CLASSPATH) fonts are
        // never exported and so never arrive over the wire.
        return ImportFont(
            tenantId = tenantId,
            catalogKey = command.catalogKey,
            slug = resource.slug,
            name = resource.name,
            kind = resource.kind,
            variants = resource.variants.map { entry ->
                ImportFontVariant(
                    weight = entry.weight,
                    italic = entry.italic,
                    source = app.epistola.suite.fonts.model.FontVariantSource.ASSET,
                    assetKey = app.epistola.suite.common.ids.AssetKey.of(java.util.UUID.fromString(entry.assetSlug)),
                )
            },
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
            catalogKey = command.catalogKey,
            id = app.epistola.suite.common.ids.AssetKey.of(resource.slug),
            name = resource.name,
            mediaType = AssetMediaType.fromMimeType(resource.mediaType),
            content = content,
            width = resource.width,
            height = resource.height,
        ).execute()
    }
}

/**
 * Thrown when a catalog references dependencies that are not included in the manifest.
 */
class InvalidCatalogException(message: String) : RuntimeException(message)
