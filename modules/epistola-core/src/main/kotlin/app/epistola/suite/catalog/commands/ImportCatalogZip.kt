package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.RESOURCE_INSTALL_ORDER
import app.epistola.suite.catalog.SemVer
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
 * Imports a catalog from a ZIP archive into a catalog of [catalogType].
 *
 * A slug that already exists with a *different* type is rejected (it would flip
 * ownership semantics). Otherwise:
 *
 * - **AUTHORED** — your editable copy. Resources are upserted in place (no
 *   stale-prune). When the import *creates* the catalog and the manifest
 *   carries a clean released SemVer + fingerprint, that becomes the initial
 *   release; importing into an existing AUTHORED catalog is just an edit.
 * - **SUBSCRIBED** — a managed mirror. The ZIP *is* the upgrade transport
 *   (instead of a source URL): full `UpgradeCatalog` parity — conflict-checked
 *   before mutating, resources replaced, stale pruned, abort-on-failed-install
 *   (no silent half-upgrade), and `installed_*` advanced from the manifest.
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
    /**
     * SUBSCRIBED only: true when a resource install FAILED, so stale resources
     * were NOT pruned and `installed_*` was NOT advanced — the catalog stays on
     * its previous release and a re-import retries (parity with `UpgradeCatalog`,
     * never a silent half-upgrade).
     */
    val aborted: Boolean = false,
)

@Component
class ImportCatalogZipHandler(
    private val objectMapper: ObjectMapper,
    private val protocolMapper: ProtocolMapper,
    private val sizeLimits: app.epistola.suite.catalog.CatalogSizeLimits,
    private val jdbi: org.jdbi.v3.core.Jdbi,
    private val analyzer: CatalogUpgradeAnalyzer,
) : CommandHandler<ImportCatalogZip, ImportCatalogZipResult> {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val canonicalizer = CatalogCanonicalizer(objectMapper)

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

        // A ZIP import targets a catalog *type*. A slug that already exists
        // with a different type is a conflict — importing AUTHORED content over
        // a SUBSCRIBED mirror (or vice-versa) would silently flip ownership
        // semantics. Same type is fine: AUTHORED ⇒ in-place edit; SUBSCRIBED ⇒
        // an upgrade (the ZIP is the transport instead of a source URL).
        val existingCatalog = GetCatalog(command.tenantKey, catalogKey).query()
        if (existingCatalog != null && existingCatalog.type != command.catalogType) {
            throw IllegalArgumentException(
                "Catalog '${catalogKey.value}' already exists as ${existingCatalog.type} — " +
                    "cannot import it as ${command.catalogType}.",
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
                        is app.epistola.catalog.protocol.DependencyRef.CodeList -> "code list '${dep.slug}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.Font -> "font '${dep.slug}' from catalog '${dep.catalogKey}'"
                    }
                }
                throw IllegalArgumentException(
                    "Catalog '${catalogKey.value}' has unmet dependencies: $details. " +
                        "Import or create these resources first.",
                )
            }
        }

        val tenantId = TenantId(command.tenantKey)
        val manifestSlugs = manifest.resources.groupBy({ it.type }, { it.slug })

        // SUBSCRIBED parity with UpgradeCatalog: validate cross-catalog
        // conflicts BEFORE any mutation (a stale resource still referenced from
        // another catalog blocks the whole import). New catalog ⇒ nothing
        // installed ⇒ no stale ⇒ no conflicts.
        val staleBefore = if (command.catalogType == CatalogType.SUBSCRIBED) {
            val installed = analyzer.installedByType(command.tenantKey, catalogKey)
            val stale = analyzer.computeStale(installed, manifestSlugs)
            if (stale.isNotEmpty()) {
                val conflicts = analyzer.findConflicts(command.tenantKey, catalogKey, stale)
                if (conflicts.isNotEmpty()) throw CatalogUpgradeConflictException(conflicts)
            }
            stale
        } else {
            emptyList()
        }

        // Ensure the catalog row exists with the requested type before install.
        if (existingCatalog == null) {
            when (command.catalogType) {
                CatalogType.AUTHORED -> CreateCatalog(
                    tenantKey = command.tenantKey,
                    id = catalogKey,
                    name = manifest.catalog.name,
                    description = manifest.catalog.description,
                ).execute()

                CatalogType.SUBSCRIBED -> insertSubscribedCatalog(
                    command.tenantKey,
                    catalogKey,
                    manifest.catalog.name,
                    manifest.catalog.description,
                )
            }
        }

        // Install resources in dependency order
        val ordered = manifest.resources.sortedBy { RESOURCE_INSTALL_ORDER[it.type] ?: 99 }
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

        val anyFailed = results.any { it.status == InstallStatus.FAILED }

        if (command.catalogType == CatalogType.SUBSCRIBED) {
            // A ZIP import of a SUBSCRIBED catalog IS its upgrade — same
            // contract as a URL `UpgradeCatalog`, the ZIP just being the
            // transport instead of a source URL.
            if (anyFailed) {
                // Abort: do NOT prune stale or advance installed_* — the
                // catalog stays on its previous release and a re-import
                // retries (never a silent half-upgrade).
                val failed = results.filter { it.status == InstallStatus.FAILED }
                logger.error(
                    "SUBSCRIBED ZIP import of '{}' ABORTED — {} resource(s) failed; not pruning stale, not advancing installed version: {}",
                    catalogKey.value,
                    failed.size,
                    failed.joinToString { "${it.type}/${it.slug}: ${it.errorMessage}" },
                )
                return@runAsImport ImportCatalogZipResult(catalogKey, manifest.catalog.name, results, aborted = true)
            }
            analyzer.removeStale(command.tenantKey, catalogKey, staleBefore)
            updateSubscribedInstalledState(
                command.tenantKey,
                catalogKey,
                manifest.release.version,
                manifest.release.fingerprint,
                objectMapper.writeValueAsString(subscribedPerResourceFingerprints(manifest, entries)),
                manifest.catalog.name,
                manifest.catalog.description,
            )
        } else if (existingCatalog == null &&
            manifest.release.fingerprint != null &&
            SemVer.parseOrNull(manifest.release.version) != null &&
            !anyFailed
        ) {
            // AUTHORED: adopt the published version as the *initial* release
            // only when this import created the catalog. A release is an
            // authorship act — importing into an existing AUTHORED catalog is
            // an edit (drift/"unreleased changes" is the correct signal and
            // the owner releases deliberately); `-dev`/legacy versions are
            // nothing real to adopt. Skipped if any resource failed.
            try {
                ReleaseCatalogVersion(
                    tenantKey = command.tenantKey,
                    catalogKey = catalogKey,
                    version = manifest.release.version,
                    notes = "Initial release imported from catalog ZIP (publisher: ${manifest.publisher.name})",
                ).execute()
                logger.info(
                    "Catalog '{}' created from ZIP — adopted imported release {} as the initial release",
                    catalogKey.value,
                    manifest.release.version,
                )
            } catch (e: CatalogReleaseVersionException) {
                // Defensive only — a brand-new catalog has no prior releases.
                logger.info(
                    "Did not adopt imported release {} for new catalog '{}': {}",
                    manifest.release.version,
                    catalogKey.value,
                    e.message,
                )
            }
        }

        ImportCatalogZipResult(
            catalogKey = catalogKey,
            catalogName = manifest.catalog.name,
            results = results,
        )
    }

    /** Inserts an empty SUBSCRIBED catalog row (no source URL — ZIP-sourced). */
    private fun insertSubscribedCatalog(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        name: String,
        description: String?,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (id, tenant_key, name, description, type, created_at, updated_at)
                VALUES (:c, :t, :name, :description, 'SUBSCRIBED', NOW(), NOW())
                """,
            )
                .bind("c", catalogKey)
                .bind("t", tenantKey)
                .bind("name", name)
                .bind("description", description)
                .execute()
        }
    }

    /**
     * Advances the SUBSCRIBED install pointer from the imported ZIP — the same
     * `installed_*` state a URL [RegisterCatalog]/[UpgradeCatalog] records,
     * so the upgrade preview / drift detection work identically. Run last,
     * only on a fully successful import.
     */
    private fun updateSubscribedInstalledState(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        version: String,
        fingerprint: String?,
        resourceFingerprintsJson: String,
        name: String,
        description: String?,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET installed_release_version = :version, installed_fingerprint = :fingerprint,
                    installed_resource_fingerprints = :resourceFingerprints::jsonb,
                    name = :name, description = :description, updated_at = NOW()
                WHERE tenant_key = :t AND id = :c
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("fingerprint", fingerprint)
                .bind("resourceFingerprints", resourceFingerprintsJson)
                .bind("name", name)
                .bind("description", description)
                .execute()
        }
    }

    /**
     * Per-resource source-side digests from the ZIP bytes — the SUBSCRIBED
     * upgrade-diff baseline, computed with the **exact same** canonicalization
     * as a URL source ([CatalogCanonicalizer.perResourceFingerprintsFromSerializedDetails]),
     * keyed `"$type/$slug"`, asset bytes folded in. Equals what the publisher
     * stamped (deterministic round-trip).
     */
    private fun subscribedPerResourceFingerprints(
        manifest: CatalogManifest,
        entries: Map<String, ByteArray>,
    ): Map<String, String> {
        val detailBytesByKey = manifest.resources.mapNotNull { entry ->
            entries[entry.detailUrl.removePrefix("./")]?.let { "${entry.type}/${entry.slug}" to it }
        }.toMap()
        return canonicalizer.perResourceFingerprintsFromSerializedDetails(detailBytesByKey) { contentUrl ->
            entries[contentUrl.removePrefix("./")]
        }
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
                themeId = resource.themeId,
                themeCatalogKey = if (resource.themeId != null) resource.themeCatalogKey ?: catalogKey.value else null,
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

        is AttributeResource -> {
            val bindingCatalog = resource.codeListBinding?.let {
                it.catalogKey?.let(CatalogKey::of) ?: catalogKey
            }
            val bindingSlug = resource.codeListBinding?.slug?.let(app.epistola.suite.common.ids.CodeListKey::of)
            ImportAttribute(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = resource.slug,
                displayName = resource.name,
                allowedValues = resource.allowedValues ?: emptyList(),
                codeListCatalogKey = bindingCatalog,
                codeListSlug = bindingSlug,
            ).execute()
        }

        is app.epistola.catalog.protocol.CodeListResource -> ImportCodeList(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.slug,
            displayName = resource.name,
            description = resource.description,
            entries = resource.entries.map { wire ->
                app.epistola.suite.attributes.codelists.model.CodeListEntry(
                    code = wire.code,
                    label = wire.label,
                    sortOrder = wire.sortOrder,
                    hidden = wire.hidden,
                )
            },
        ).execute()

        is FontResource -> app.epistola.suite.fonts.commands.ImportFont(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.slug,
            name = resource.name,
            kind = resource.kind,
            // Every variant's binary rode the ZIP as an `AssetResource`
            // installed in this same catalog (asset slug = asset UUID).
            // System (CLASSPATH) fonts are never exported.
            variants = resource.variants.map { entry ->
                app.epistola.suite.fonts.commands.ImportFontVariant(
                    weight = entry.weight,
                    italic = entry.italic,
                    source = app.epistola.suite.fonts.model.FontVariantSource.ASSET,
                    assetKey = AssetKey.of(java.util.UUID.fromString(entry.assetSlug)),
                )
            },
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

            // Catalog-scoped: code lists. Matches the same `catalog_key + slug`
            // shape as themes/stencils so the look-up cost is one PK probe.
            val codeListDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.CodeList>()
            if (codeListDeps.isNotEmpty()) {
                handle.createQuery("SELECT catalog_key, slug FROM code_lists WHERE tenant_key = :tenantKey AND slug IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", codeListDeps.map { it.slug })
                    .map { rs, _ -> "codeList:${rs.getString("catalog_key")}:${rs.getString("slug")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: fonts. Same `catalog_key + slug` PK-probe shape
            // as themes/stencils/code lists.
            val fontDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Font>()
            if (fontDeps.isNotEmpty()) {
                handle.createQuery("SELECT catalog_key, slug FROM fonts WHERE tenant_key = :tenantKey AND slug IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", fontDeps.map { it.slug })
                    .map { rs, _ -> "font:${rs.getString("catalog_key")}:${rs.getString("slug")}" }
                    .list()
                    .let { found.addAll(it) }
            }
        }

        return deps.filter { dep ->
            when (dep) {
                is app.epistola.catalog.protocol.DependencyRef.Theme -> "theme:${dep.catalogKey}:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Stencil -> "stencil:${dep.catalogKey}:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Asset -> "asset:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.CodeList -> "codeList:${dep.catalogKey}:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Font -> "font:${dep.catalogKey}:${dep.slug}" !in found
            }
        }
    }
}
