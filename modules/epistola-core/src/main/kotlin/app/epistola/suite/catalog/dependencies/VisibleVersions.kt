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
import java.sql.ResultSet
import java.util.UUID

/**
 * One catalog version a version can use resources of, and how a message addresses it: [ref] is the
 * release a declaration pins or the draft it names, the addressed version for the catalog itself,
 * and null for a catalog's next version (a declaration naming neither a release nor a draft).
 */
data class VisibleVersion(val catalog: CatalogId, val versionId: UUID, val ref: CatalogReleaseRef?)

/**
 * Which catalogs a picker reads: those the version can use ([DECLARED]), or those it could declare
 * ([UNDECLARED]), offered apart so using one is a deliberate step (declaring it first).
 */
enum class Reach {
    /** The catalog itself and each catalog it declares: what it can use. */
    DECLARED,

    /** The tenant's other catalogs, each at the version declaring it would name: what it could use once declared. */
    UNDECLARED,
}

/**
 * The catalog versions one version of a catalog can use resources of (contract ADR 0003): its own,
 * then each catalog it declares, in declaration order, at the release or draft the declaration
 * names. Not transitive: what a declared catalog declares in turn is not visible. A declaration of a
 * catalog, release or draft the tenant does not have makes nothing visible.
 *
 * The one rule every picker query reads by ([predicate], [order], [bindVisible]), so a picker offers
 * what the version renders with and nothing it would have to declare first; [undeclared] is what it
 * could declare, which a picker offers apart.
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

    /**
     * The tenant's catalogs version [message] of [catalog] does not declare and could, by namespace and
     * key, each at the version declaring it names (as [UsedCatalogDeclarer] declares): its next
     * version when it can be changed here ([editingEnabled] and not supplied by Epistola), else its
     * highest release. Left out: a catalog under the key of the catalog itself or of a declaration,
     * whose alias would clash, and one with no such version.
     *
     * @throws CatalogNotFoundException when the tenant has no [catalog].
     */
    fun undeclared(handle: Handle, catalog: CatalogId, message: Any?, editingEnabled: Boolean): List<VisibleVersion> {
        handle.findCatalog(catalog) ?: throw CatalogNotFoundException(catalog.key)
        val ownId = handle.versionOf(catalog, message).versionId ?: return emptyList()
        return handle.createQuery(
            """
            SELECT c.namespace, c.catalog_key, v.version_id, v.state, v.version, v.version_key, v.version_id = c.next_version_id AS is_next
            FROM catalogs c
            LEFT JOIN catalog_current_releases cr ON cr.tenant_key = c.tenant_key AND cr.catalog_id = c.catalog_id
            LEFT JOIN catalog_versions rv ON rv.tenant_key = c.tenant_key AND rv.catalog_id = c.catalog_id AND rv.state = 'released' AND rv.version = cr.version
            JOIN catalog_versions v ON v.tenant_key = c.tenant_key AND v.version_id = CASE
                WHEN :editing AND NOT COALESCE(c.source_url LIKE 'classpath:%', FALSE) THEN c.next_version_id
                ELSE rv.version_id END
            WHERE c.tenant_key = :t
              AND c.catalog_key <> :ownKey
              AND NOT EXISTS (
                  SELECT 1 FROM catalog_version_dependencies d
                  WHERE d.tenant_key = c.tenant_key AND d.version_id = :v
                    AND (d.alias = c.catalog_key OR (d.registry = c.registry AND d.namespace = c.namespace AND d.dependency_key = c.catalog_key)))
            ORDER BY c.catalog_key, c.namespace
            """,
        )
            .bind("t", catalog.tenantKey)
            .bind("v", ownId)
            .bind("ownKey", catalog.key.value)
            .bind("editing", editingEnabled)
            .map { rs, _ -> visibleVersion(rs, catalog) }
            .list()
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
        .map { rs, _ -> visibleVersion(rs, catalog) }
        .list()

    /** A row with the catalog's `namespace` and `catalog_key`, and the version's `version_id`, `state`, `version`, `version_key` and `is_next`. */
    private fun visibleVersion(rs: ResultSet, of: CatalogId): VisibleVersion {
        val id = CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key")), TenantId(of.tenantKey))
        val draft = when {
            rs.getBoolean("is_next") -> null
            rs.getString("state") == "draft" -> DraftRef.Id(rs.getString("version_key"))
            else -> DraftRef.Released(rs.getString("version"))
        }
        return VisibleVersion(id, rs.getObject("version_id", UUID::class.java), draft?.let { CatalogReleaseRef(id, it) })
    }

    /** [of] for [Reach.DECLARED], [undeclared] for [Reach.UNDECLARED]. */
    fun reach(handle: Handle, catalog: CatalogId, message: Any?, reach: Reach, editingEnabled: Boolean): List<VisibleVersion> = when (reach) {
        Reach.DECLARED -> of(handle, catalog, message)
        Reach.UNDECLARED -> undeclared(handle, catalog, message, editingEnabled)
    }
}
