package app.epistola.suite.api.v1

import app.epistola.api.CatalogsApi
import app.epistola.api.model.CatalogDto
import app.epistola.api.model.CatalogListResponse
import app.epistola.api.model.ImportCatalogResponse
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.queries.ListCatalogs
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
                    CatalogDto(
                        id = catalog.id.value,
                        name = catalog.name,
                        description = catalog.description,
                        type = CatalogDto.Type.valueOf(catalog.type.name),
                    )
                },
            ),
        )
    }

    override fun importCatalog(
        tenantId: String,
        file: MultipartFile,
        catalogType: String,
    ): ResponseEntity<ImportCatalogResponse> {
        val tenantKey = TenantKey.of(tenantId)
        val type = CatalogType.valueOf(catalogType.ifBlank { "AUTHORED" })

        val result = ImportCatalogZip(
            tenantKey = tenantKey,
            zipBytes = file.bytes,
            catalogType = type,
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
            ),
        )
    }
}
