// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.DependencyResolver
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.RESOURCE_INSTALL_ORDER
import app.epistola.suite.catalog.migrations.CatalogMigrationContext
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Installs a subscribed catalog from its source URL.
 *
 * A catalog is one install unit: every resource in the manifest is installed, never a subset. A
 * partially installed catalog cannot say what "installed 1.2.0" means -- the version and fingerprint
 * on the catalog row are the publisher's statement about the whole release -- and every rule built
 * on top (a cross-catalog dependency, an upgrade diff) becomes conditional on what the installer
 * happened to pick. A publisher who wants independent adoption publishes more than one catalog.
 * See issue #850.
 */
data class InstallFromCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
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
    private val sizeLimits: CatalogSizeLimits,
    transactionManager: PlatformTransactionManager,
) : CommandHandler<InstallFromCatalog, List<InstallResult>> {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Phase two runs here. The command is [SelfManagedTransaction] because phase one talks HTTP, and
     * holding a pooled connection across somebody else's server is what that opt-out exists to
     * avoid; this opens a transaction once the network is done with.
     */
    private val installTransaction = TransactionTemplate(transactionManager)

    /**
     * One resource fetched and held in memory, waiting for the transaction.
     *
     * A resource whose fetch failed is staged as a failure rather than thrown: the caller's contract
     * is a per-resource [InstallResult], and an upgrade reads those to decide it aborted. Only a
     * wire-version gate failure and an over-budget catalog reject the whole install outright.
     */
    private data class StagedResource(
        val entry: ResourceEntry,
        val resource: CatalogResource? = null,
        /** Pre-fetched asset bytes; null for every other resource type. */
        val assetContent: ByteArray? = null,
        val fetchError: String? = null,
    )

    override fun handle(command: InstallFromCatalog): List<InstallResult> = CatalogImportContext.runAsImport {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${command.catalogKey}")

        val sourceUrl = catalog.sourceUrl
            ?: throw IllegalStateException("Catalog has no source URL: ${command.catalogKey}")

        val migratedManifest = catalogClient.fetchMigratedManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value)
        val manifest = migratedManifest.manifest
        val catalogCtx = migratedManifest.catalog

        // The whole manifest, always. `DependencyResolver` still runs: it cannot add anything to a
        // set that is already complete, but it validates that every same-catalog reference points at
        // a resource the manifest actually declares, which is the check that used to matter when a
        // subset was installable and still catches a malformed manifest.
        val resourcesToInstall = dependencyResolver.resolve(
            manifest.resources,
            manifest,
            sourceUrl,
            catalog.sourceAuthType,
            catalog.sourceAuthCredential?.value,
            catalogCtx,
        )

        // Install in dependency order: assets → attributes → themes → stencils → templates
        val ordered = resourcesToInstall.sortedBy { RESOURCE_INSTALL_ORDER[it.type] ?: 99 }

        // Phase one: fetch everything, holding no database connection. A wire-version gate failure
        // (too new/old/unknown) rejects the whole install here and surfaces the dedicated operator
        // remediation, rather than being downgraded to one FAILED resource — matching the manifest
        // gate above and the ZIP path in ImportCatalogZip.
        val staged = stage(command, ordered, sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential?.value, catalogCtx)

        // Phase two: one transaction for the whole catalog. A resource that fails still reports
        // FAILED to the caller, but the transaction is rolled back, so the install leaves nothing
        // behind rather than a half-installed catalog nobody chose (#850).
        installTransaction.execute { status ->
            val results = staged.map { item ->
                if (item.fetchError != null) {
                    return@map InstallResult(type = item.entry.type, slug = item.entry.slug, status = InstallStatus.FAILED, errorMessage = item.fetchError)
                }
                try {
                    val installStatus = installResource(command, item, manifest.release.version)
                    InstallResult(type = item.entry.type, slug = item.entry.slug, status = installStatus)
                } catch (e: Exception) {
                    logger.error("Failed to install {} '{}' from catalog '{}': {}", item.entry.type, item.entry.slug, command.catalogKey, e.message, e)
                    InstallResult(type = item.entry.type, slug = item.entry.slug, status = InstallStatus.FAILED, errorMessage = e.message)
                }
            }
            if (results.any { it.status == InstallStatus.FAILED }) {
                status.setRollbackOnly()
            }
            results
        }
    }

    /**
     * Fetches every resource detail, and the bytes of every asset, before anything is written.
     *
     * Holding the catalog in memory is what buys atomicity: the alternative is HTTP inside the
     * transaction, which keeps a pooled connection open across somebody else's server. The ZIP path
     * already works this way — it holds a whole archive — so the same budget applies, and a catalog
     * that would not have fitted in an archive does not get in through the URL instead.
     */
    private fun stage(
        command: InstallFromCatalog,
        ordered: List<ResourceEntry>,
        sourceUrl: String,
        authType: AuthType,
        credential: String?,
        catalogCtx: CatalogMigrationContext,
    ): List<StagedResource> {
        val budget = sizeLimits.maxDecompressedSize.toBytes()
        var fetched = 0L
        return ordered.map { entry ->
            try {
                val detail = catalogClient.fetchResourceDetail(entry.type, entry.detailUrl, sourceUrl, authType, credential, catalogCtx)
                val resource = detail.resource
                val assetContent = (resource as? AssetResource)?.let {
                    catalogClient.fetchBinaryContent(it.contentUrl, sourceUrl, authType, credential)
                }
                fetched += assetContent?.size?.toLong() ?: 0L
                if (fetched > budget) {
                    throw CatalogTooLargeException(command.catalogKey, fetched, budget)
                }
                StagedResource(entry, resource, assetContent)
            } catch (e: CatalogSchemaException) {
                throw e
            } catch (e: CatalogTooLargeException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to fetch {} '{}' from catalog '{}': {}", entry.type, entry.slug, command.catalogKey, e.message, e)
                StagedResource(entry, fetchError = e.message ?: "could not be fetched from the catalog source")
            }
        }
    }

    private fun installResource(
        command: InstallFromCatalog,
        item: StagedResource,
        releaseVersion: String,
    ): InstallStatus = when (val resource = requireNotNull(item.resource) { "Resource '${item.entry.slug}' was not staged" }) {
        is TemplateResource -> installTemplate(command, resource, releaseVersion)
        is ThemeResource -> installTheme(command, resource)
        is StencilResource -> installStencil(command, resource)
        is AttributeResource -> installAttribute(command, resource)
        is AssetResource -> installAsset(command, resource, requireNotNull(item.assetContent) { "Asset '${resource.slug}' was not staged" })
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
            pdfaEnabled = resource.pdfaEnabled,
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
        content: ByteArray,
    ): InstallStatus {
        val tenantId = TenantId(command.tenantKey)

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
 * Thrown when a catalog's content exceeds the budget an install may hold in memory.
 *
 * The same ceiling a ZIP import enforces (`epistola.catalog.max-decompressed-size`), applied to the
 * URL-subscribed path so a catalog that would be refused as an archive is not accepted as a source.
 */
class CatalogTooLargeException(
    val catalogKey: CatalogKey,
    val fetchedBytes: Long,
    val budgetBytes: Long,
) : RuntimeException(
    "Catalog '${catalogKey.value}' content exceeds the ${budgetBytes / (1024 * 1024)} MB install budget " +
        "(reached ${fetchedBytes / (1024 * 1024)} MB). Ask the publisher to split it into smaller catalogs, " +
        "or raise epistola.catalog.max-decompressed-size.",
)

/**
 * Thrown when a catalog references dependencies that are not included in the manifest.
 */
class InvalidCatalogException(message: String) : RuntimeException(message)
