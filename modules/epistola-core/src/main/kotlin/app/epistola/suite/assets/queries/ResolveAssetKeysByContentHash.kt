// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets.queries

import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The assets row holding each of these content hashes, for a tenant.
 *
 * Export needs a font face's bytes, and the wire identifies a face by its content hash rather than
 * by an asset key -- from wire v7 a face's binary is not separately addressable, so it has no key
 * on the wire to look up. The key it happens to have locally depends on how it got here: a face
 * uploaded through this suite carries the generated key its upload minted, while one installed
 * from a catalog carries a key derived from the hash. Resolving by hash is right for both, and is
 * exactly what a content-addressed store makes meaningful -- any row carrying that hash carries
 * those bytes.
 */
data class ResolveAssetKeysByContentHash(
    override val tenantKey: TenantKey,
    val contentHashes: Collection<String>,
) : Query<Map<String, AssetKey>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ResolveAssetKeysByContentHashHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ResolveAssetKeysByContentHash, Map<String, AssetKey>> {
    override fun handle(query: ResolveAssetKeysByContentHash): Map<String, AssetKey> {
        val hashes = query.contentHashes.distinct()
        if (hashes.isEmpty()) return emptyMap()
        return jdbi.withHandle<Map<String, AssetKey>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT DISTINCT ON (content_hash) content_hash, id::text AS asset_key
                FROM assets
                WHERE tenant_key = :tenantKey AND content_hash IN (<hashes>)
                ORDER BY content_hash, created_at
                """,
            )
                .bind("tenantKey", query.tenantKey)
                .bindList("hashes", hashes)
                .map { rs, _ -> rs.getString("content_hash") to AssetKey.of(rs.getString("asset_key")) }
                .list()
                .toMap()
        }
    }
}
