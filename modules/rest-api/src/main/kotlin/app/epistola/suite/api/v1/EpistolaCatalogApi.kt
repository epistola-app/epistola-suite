package app.epistola.suite.api.v1

import app.epistola.api.CatalogsApi
import app.epistola.api.model.CatalogDto
import app.epistola.api.model.CatalogListResponse
import app.epistola.api.model.CatalogUpgradeDiff
import app.epistola.api.model.ImportCatalogResponse
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.AuthoredImportMode
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.catalog.queries.UpgradeResourceChange
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api")
class EpistolaCatalogApi : CatalogsApi {

    override fun listCatalogs(tenantId: String): ResponseEntity<CatalogListResponse> {
        val tenantKey = TenantKey.of(tenantId)
        val catalogs = ListCatalogs(tenantKey).query()
        return ResponseEntity.ok(
            CatalogListResponse(
                items = catalogs.map { catalog ->
                    val authored = catalog.type == CatalogType.AUTHORED
                    CatalogDto(
                        id = catalog.id.value,
                        name = catalog.name,
                        description = catalog.description,
                        type = CatalogDto.Type.valueOf(catalog.type.name),
                        releasedVersion = if (authored) catalog.releasedVersion else catalog.installedReleaseVersion,
                        fingerprint = if (authored) catalog.releasedFingerprint else catalog.installedFingerprint,
                    )
                },
            ),
        )
    }

    override fun previewCatalogUpgrade(
        tenantId: String,
        catalogId: String,
    ): ResponseEntity<CatalogUpgradeDiff> {
        val diff = PreviewCatalogUpgrade(
            tenantKey = TenantKey.of(tenantId),
            catalogKey = CatalogKey.of(catalogId),
        ).query()

        fun keys(changes: List<UpgradeResourceChange>) = changes.map { "${it.type}/${it.slug}" }

        return ResponseEntity.ok(
            CatalogUpgradeDiff(
                catalogId = diff.catalogKey.value,
                newVersion = diff.newVersion,
                upgradeAvailable = diff.hasChanges,
                added = keys(diff.added),
                removed = keys(diff.removed),
                changed = keys(diff.changed),
                unchanged = keys(diff.unchanged),
                conflicts = diff.conflicts,
                blockedByConflicts = diff.hasConflicts,
                previousVersion = diff.previousVersion,
            ),
        )
    }

    override fun importCatalog(
        tenantId: String,
        file: MultipartFile,
        catalogType: String,
        authoredMode: String,
    ): ResponseEntity<ImportCatalogResponse> {
        val tenantKey = TenantKey.of(tenantId)
        val type = CatalogType.valueOf(catalogType.ifBlank { "AUTHORED" })
        val mode = AuthoredImportMode.valueOf(authoredMode.ifBlank { "MERGE" })

        val result = ImportCatalogZip(
            tenantKey = tenantKey,
            zipBytes = file.bytes,
            catalogType = type,
            authoredMode = mode,
            // REST is non-interactive: an AUTHORED migratable-old import migrates
            // without a confirmation round-trip (the UI prompts; REST does not).
            confirmMigration = true,
        ).execute()

        val installed = result.results.count { it.status == InstallStatus.INSTALLED }
        val updated = result.results.count { it.status == InstallStatus.UPDATED }
        val failed = result.results.count { it.status == InstallStatus.FAILED }

        return ResponseEntity.ok(
            ImportCatalogResponse(
                catalogKey = result.catalogKey.value,
                catalogName = result.catalogName,
                installed = installed,
                updated = updated,
                failed = failed,
                total = result.results.size,
                aborted = result.aborted,
            ),
        )
    }
}
