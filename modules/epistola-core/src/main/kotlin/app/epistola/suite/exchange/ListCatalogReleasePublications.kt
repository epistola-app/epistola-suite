// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

data class ListCatalogReleasePublications(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<List<CatalogReleasePublicationSummary>>,
    RequiresPermission {
    override val permission = Permission.CATALOG_VIEW
}

data class CatalogReleasePublicationSummary(
    val id: UUID,
    val version: String,
    val namespace: String?,
    val status: String,
    val remotePublicationId: UUID?,
    val attempts: Int,
    val lastError: String?,
    val archiveRetained: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

@Component
class ListCatalogReleasePublicationsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCatalogReleasePublications, List<CatalogReleasePublicationSummary>> {
    override fun handle(query: ListCatalogReleasePublications): List<CatalogReleasePublicationSummary> = jdbi.withHandle<List<CatalogReleasePublicationSummary>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, version, namespace, status, remote_publication_id, attempts, last_error,
                       archive IS NOT NULL AS archive_retained, created_at, updated_at
                FROM catalog_release_publications
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                ORDER BY created_at DESC
                """,
        ).bind("tenantKey", query.tenantKey).bind("catalogKey", query.catalogKey).map { rs, _ ->
            CatalogReleasePublicationSummary(
                rs.getObject("id", UUID::class.java),
                rs.getString("version"),
                rs.getString("namespace"),
                rs.getString("status"),
                rs.getObject("remote_publication_id", UUID::class.java),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getBoolean("archive_retained"),
                rs.getObject("created_at", OffsetDateTime::class.java),
                rs.getObject("updated_at", OffsetDateTime::class.java),
            )
        }.list()
    }
}
