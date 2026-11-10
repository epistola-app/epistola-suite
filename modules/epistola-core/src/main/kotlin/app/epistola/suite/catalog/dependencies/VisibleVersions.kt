// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.Query
import java.util.UUID

/**
 * One catalog version a version can use resources of, and how a message addresses it: [ref] is the
 * release a declaration pins or the draft it names, the addressed version for the catalog itself,
 * and null for a catalog's next version (a declaration naming neither a release nor a draft).
 */
data class VisibleVersion(val catalog: CatalogId, val versionId: UUID, val ref: CatalogReleaseRef?)

/**
 * The catalog versions one version of a catalog can use resources of (contract ADR 0003): its own,
 * then each catalog it declares, in declaration order, at the release or draft the declaration
 * names. Not transitive: what a declared catalog declares in turn is not visible. A declaration of a
 * catalog, release or draft the tenant does not have makes nothing visible.
 *
 * The one rule every picker query reads by ([predicate], [order], [bindVisible]), so a picker offers
 * what the version renders with and nothing it would have to declare first.
 */
object VisibleVersions {

    /**
     * The versions version [message] addresses of [catalog] can use, its own first; empty when the
     * catalog has no such version.
     *
     * @throws CatalogNotFoundException when the tenant has no [catalog].
     */
    fun of(handle: Handle, catalog: CatalogId, message: Any?): List<VisibleVersion> {
        handle.findCatalog(catalog) ?: throw CatalogNotFoundException(catalog.key)
        val ownId = handle.versionOf(catalog, message).versionId ?: return emptyList()
        val ownRef = (message as? CatalogReleaseScoped)?.catalogVersion?.takeIf { it.catalog == catalog }
        return listOf(VisibleVersion(catalog, ownId, ownRef)) + declared(handle, catalog, ownId)
    }

    /** A predicate that the row aliased [alias] is of a visible version; bind them with [bindVisible]. */
    fun predicate(alias: String): String = "$alias.version_id = ANY(:visible)"

    /** Orders rows aliased [alias] by their version's place: the catalog's own first, then the declarations'. */
    fun order(alias: String): String = "array_position(:visible, $alias.version_id)"

    /** Binds [visible] for [predicate] and [order]. */
    fun Query.bindVisible(visible: List<VisibleVersion>): Query = bindArray("visible", UUID::class.java, visible.map { it.versionId })

    // A draft declaration names its draft by `target_version_id`; a pin names its release by number,
    // read as ContentVersions.release reads it, so a release received after the pin is found too. A
    // declaration naming neither, or a draft since released, reads the catalog's next version, as
    // CatalogDependencyStore.declarationsOf reads a draft only while it is one.
    private fun declared(handle: Handle, catalog: CatalogId, versionId: UUID): List<VisibleVersion> = handle.createQuery(
        """
        SELECT c.namespace, c.catalog_key, v.version_id, v.state, v.version, v.version_key, d.release_version IS NULL AND dv.version_id IS NULL AS is_next
        FROM catalog_version_dependencies d
        JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.registry = d.registry AND c.namespace = d.namespace AND c.catalog_key = d.dependency_key
        LEFT JOIN catalog_versions dv ON dv.tenant_key = d.tenant_key AND dv.version_id = d.target_version_id AND dv.state = 'draft'
        LEFT JOIN catalog_versions rv ON rv.tenant_key = d.tenant_key AND rv.catalog_id = c.catalog_id AND rv.state = 'released' AND rv.version = d.release_version
        JOIN catalog_versions v ON v.tenant_key = d.tenant_key
            AND v.version_id = COALESCE(dv.version_id, CASE WHEN d.release_version IS NOT NULL THEN rv.version_id ELSE c.next_version_id END)
        WHERE d.tenant_key = :t AND d.version_id = :v
        ORDER BY d.position
        """,
    )
        .bind("t", catalog.tenantKey)
        .bind("v", versionId)
        .map { rs, _ ->
            val id = CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key")), TenantId(catalog.tenantKey))
            val draft = when {
                rs.getBoolean("is_next") -> null
                rs.getString("state") == "draft" -> DraftRef.Id(rs.getString("version_key"))
                else -> DraftRef.Released(rs.getString("version"))
            }
            VisibleVersion(id, rs.getObject("version_id", UUID::class.java), draft?.let { CatalogReleaseRef(id, it) })
        }
        .list()
}
