// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

internal fun mimeTypeToExtension(mimeType: String): String = when (mimeType) {
    "image/png" -> ".png"
    "image/jpeg" -> ".jpg"
    "image/webp" -> ".webp"
    "image/svg+xml" -> ".svg"
    else -> ""
}

data class ExportAssets(
    override val tenantKey: TenantKey,
    val catalog: CatalogId? = null,
    val assetIds: List<String>? = null,
) : Query<List<ImageResource>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantKey) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ExportAssetsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) : QueryHandler<ExportAssets, List<ImageResource>> {

    override fun handle(query: ExportAssets): List<ImageResource> = jdbi.withHandle<List<ImageResource>, Exception> { handle ->
        // Images only: an image is stored in its released form (ADR 0033); a file kept from a font
        // face is not exported as an image. Each catalog's current version; the named catalog's when given.
        val sql = buildString {
            append("SELECT a.id, (a.payload -> 'resource')::text AS resource FROM current_images a WHERE a.tenant_key = :tenantKey AND a.media_type LIKE 'image/%'")
            if (query.catalogKey != null) append(" AND a.catalog_id = ${catalogAt("tenantKey", "catalogKey")}")
            if (query.assetIds != null) append(" AND a.id IN (<assetIds>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.catalogKey != null) q.bindCatalog("catalogKey", query.catalog)
        if (query.assetIds != null) q.bindList("assetIds", query.assetIds)
        q.map { rs, _ ->
            val resource = objectMapper.readTree(rs.getString("resource"))
            requireNotNull(resource.get("contentHash")?.takeIf { it.isString }) { "image ${rs.getString("id")} has no bytes" }
            objectMapper.treeToValue(resource, ImageResource::class.java)
        }.list()
    }
}
