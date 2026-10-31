// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.writable
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The rows a draft holds of the resource types stored as JSON (ADR 0033): one `release_resources`
 * row per resource, keyed by the draft (`version_id`) where a release's row is keyed by its number,
 * naming its content in `resource_contents`. The one writer of draft rows; reads go through
 * the function `catalog_version_resources` and the per-type views on it.
 *
 * Every write here names the version its message targets (`versionAt`), and a wire resource's row
 * (`parent_id IS NULL`): a template's variant rows are written by [VariantRows]. The database keeps the
 * rest, as it does for the relational content tables: the identity at the address
 * (`sync_draft_resource_identity`), the revision (`bump_content_revision`), `updated_at`, and the
 * catalog's `content_updated_at` when a row goes.
 */
object DraftResources {

    /**
     * The producer metadata (`resource.metadata`, as text) the targeted draft's row of [type] [key] of
     * [catalog] holds; null when it holds none or there is no such row ([ResourceMetadata]).
     */
    fun metadataOf(handle: Handle, catalog: CatalogId, type: String, key: String): String? = handle
        .createQuery(
            """
            SELECT (c.payload -> 'resource' -> 'metadata')::text
            FROM release_resources r
            JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
            WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.version_id = ${versionAt("t", "c")}
              AND r.resource_type = :type AND r.resource_key = :key
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("type", type)
        .bind("key", key)
        .map { rs, _ -> rs.getString(1) }
        .list()
        .firstOrNull()

    /**
     * Adds [type] [key] to the targeted draft of [catalog], holding [contentHash], and returns its
     * identity: the one registered at that address, or a new one. A row already there is a
     * unique violation, as an insert into a content table was.
     */
    fun insert(handle: Handle, catalog: CatalogId, type: String, key: String, contentHash: String, name: String, description: String?, actor: UUID?): UUID = handle.writable(catalog)
        .createQuery(
            """
            INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id, content_hash, name, description, created_by, updated_by)
            VALUES (:t, ${catalogAt("t", "c")}, ${versionAt("t", "c")}, :type, :key, CAST(NULL AS UUID), :hash, :name, :description, :actor, :actor)
            RETURNING resource_id
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("type", type)
        .bind("key", key)
        .bind("hash", contentHash)
        .bind("name", name)
        .bind("description", description)
        .bind("actor", actor)
        .mapTo(UUID::class.java)
        .one()

    /**
     * Points the targeted draft's row of [type] [key] at [contentHash], when its revision is still
     * [expectedRevision] (null: whatever it is). Null when there is no such row, or it moved on;
     * else the row's revision and `updated_at` after the change.
     */
    fun update(
        handle: Handle,
        catalog: CatalogId,
        type: String,
        key: String,
        contentHash: String,
        name: String,
        description: String?,
        actor: UUID?,
        expectedRevision: Long? = null,
    ): Updated? = handle.writable(catalog)
        .createQuery(
            """
            UPDATE release_resources
            SET content_hash = :hash, name = :name, description = :description, updated_by = :actor
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version_id = ${versionAt("t", "c")} AND resource_type = :type AND resource_key = :key AND parent_id IS NULL
              AND ${Revisions.MATCHES}
            RETURNING revision, updated_at
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("type", type)
        .bind("key", key)
        .bind("hash", contentHash)
        .bind("name", name)
        .bind("description", description)
        .bind("actor", actor)
        .bind("expectedRevision", expectedRevision)
        .map { rs, _ -> Updated(rs.getLong("revision"), rs.getObject("updated_at", OffsetDateTime::class.java)) }
        .findOne()
        .orElse(null)

    /** A draft row after an [update]: its revision and when it changed. */
    data class Updated(val revision: Long, val updatedAt: OffsetDateTime)

    /** Removes [type] [key] from the targeted draft of [catalog]; false when it held none. */
    fun delete(handle: Handle, catalog: CatalogId, type: String, key: String): Boolean = handle.writable(catalog)
        .createUpdate(
            "DELETE FROM release_resources WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version_id = ${versionAt("t", "c")} AND resource_type = :type AND resource_key = :key AND parent_id IS NULL",
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("type", type)
        .bind("key", key)
        .execute() > 0

    /**
     * The revision of [type] [key] in the targeted draft of [catalog], locking its row until the
     * transaction ends, so a read-modify-write of its content is not raced; null when it has none.
     */
    fun lockRevision(handle: Handle, catalog: CatalogId, type: String, key: String): Long? = handle
        .createQuery(
            """
            SELECT revision FROM release_resources
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version_id = ${versionAt("t", "c")} AND resource_type = :type AND resource_key = :key AND parent_id IS NULL
            FOR UPDATE
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("type", type)
        .bind("key", key)
        .mapTo(Long::class.java)
        .findOne()
        .orElse(null)

    /**
     * Moves the targeted draft's row of [type] [key] of [source] to [targetKey] in [target]'s current
     * version, holding [contentHash] (a payload names its key). The identity follows
     * (`sync_draft_resource_identity`). False when the source has no such row.
     */
    fun move(
        handle: Handle,
        source: CatalogId,
        type: String,
        key: String,
        target: CatalogId,
        targetKey: String,
        contentHash: String,
        /** The content the row must still hold (a relocation's guard against a concurrent edit); null for any. */
        expectedHash: String? = null,
    ): Boolean = handle.writable(source).writable(target)
        .createUpdate(
            """
            UPDATE release_resources
            SET catalog_id = ${catalogAt("t", "target")}, version_id = ${versionAt("t", "target")}, resource_key = :targetKey, content_hash = :hash
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "source")} AND version_id = ${versionAt("t", "source")} AND resource_type = :type AND resource_key = :key AND parent_id IS NULL
              AND (CAST(:expected AS TEXT) IS NULL OR content_hash = :expected)
            """,
        )
        .bind("t", source.tenantKey)
        .bindCatalog("source", source)
        .bindCatalog("target", target)
        .bind("type", type)
        .bind("key", key)
        .bind("targetKey", targetKey)
        .bind("hash", contentHash)
        .bind("expected", expectedHash)
        .execute() > 0

    /**
     * A row of a draft, with the content it names: a wire resource's (`parentKey` null) or a template's
     * variant's (`key` the variant's, `parentKey` its template's).
     */
    data class Row(
        /** Its catalog's address, `namespace/key`. */
        val catalogAddress: String,
        val type: String,
        val key: String,
        val parentId: UUID?,
        val parentKey: String?,
        val contentHash: String,
        /** The kind of its content (`resource_contents.kind`): the wire type, or a part's (`variant`). */
        val kind: String,
        val payload: String,
    )

    private val ROW_COLUMNS = """
        c.namespace || '/' || c.catalog_key AS catalog_address, r.resource_type, r.resource_key, r.parent_id,
        parent.resource_key AS parent_key, r.content_hash, rc.kind, rc.payload::text AS payload
    """

    private val ROW_FROM = """
        FROM release_resources r
        JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
        JOIN resource_contents rc ON rc.tenant_key = r.tenant_key AND rc.content_hash = r.content_hash
        LEFT JOIN release_resources parent ON parent.tenant_key = r.tenant_key AND parent.version_id = r.version_id
             AND parent.resource_id = r.parent_id AND parent.parent_id IS NULL
    """

    private fun rowOf(rs: java.sql.ResultSet) = Row(
        rs.getString("catalog_address"),
        rs.getString("resource_type"),
        rs.getString("resource_key"),
        rs.getObject("parent_id", UUID::class.java),
        rs.getString("parent_key"),
        rs.getString("content_hash"),
        rs.getString("kind"),
        rs.getString("payload"),
    )

    /**
     * The rows of the version each catalog of [tenantKey] means (the targeted draft, else the next),
     * when it is a draft, whose content refers to one of [references] (`type` to `key`) by any
     * catalog: what a relocation re-points (ADR 0042), read from `resource_references`.
     */
    fun referencing(handle: Handle, tenantKey: app.epistola.suite.common.ids.TenantKey, references: Collection<Pair<String, String>>): List<Row> {
        if (references.isEmpty()) return emptyList()
        return handle.createQuery(
            """
            SELECT $ROW_COLUMNS
            $ROW_FROM
            JOIN catalog_current_versions cv ON cv.tenant_key = r.tenant_key AND cv.version_id = r.version_id
            JOIN catalog_versions v ON v.tenant_key = r.tenant_key AND v.version_id = r.version_id AND v.state = 'draft'
            WHERE r.tenant_key = :t
              AND EXISTS (
                  SELECT 1 FROM resource_references rr
                  WHERE rr.tenant_key = r.tenant_key AND rr.content_hash = r.content_hash
                    AND (rr.resource_type || '/' || rr.resource_key) IN (<references>)
              )
            ORDER BY catalog_address, COALESCE(parent.resource_key, r.resource_key), r.resource_type, r.resource_key
            """,
        )
            .bind("t", tenantKey)
            .bindList("references", references.map { (type, key) -> "$type/$key" }.distinct())
            .map { rs, _ -> rowOf(rs) }
            .list()
    }

    /** The row of [type] [key] in the targeted draft of [catalog] and, for a template, its variants' rows. */
    fun rowsOf(handle: Handle, catalog: CatalogId, type: String, key: String): List<Row> = handle.createQuery(
        """
        SELECT $ROW_COLUMNS
        $ROW_FROM
        WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.version_id = ${versionAt("t", "c")}
          AND ((r.parent_id IS NULL AND r.resource_type = :type AND r.resource_key = :key)
               OR (r.parent_id IS NOT NULL AND parent.resource_type = :type AND parent.resource_key = :key))
        ORDER BY r.parent_id NULLS FIRST, r.resource_key
        """,
    )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("type", type)
        .bind("key", key)
        .map { rs, _ -> rowOf(rs) }
        .list()

    /**
     * Points the targeted draft's [row] of [catalog] at [contentHash], when it still holds the content
     * it was read with: a relocation re-pointing what a row names (ADR 0042). It is not an author's
     * edit, so who edited it last stays. False when the row changed or is gone.
     */
    fun repoint(handle: Handle, catalog: CatalogId, row: Row, contentHash: String): Boolean = handle.writable(catalog)
        .createUpdate(
            """
            UPDATE release_resources r SET content_hash = :hash
            WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.version_id = ${versionAt("t", "c")}
              AND r.resource_type = :type AND r.resource_key = :key AND r.content_hash = :expected
              AND (CAST(:parentKey AS TEXT) IS NULL AND r.parent_id IS NULL
                   OR r.parent_id = (SELECT p.resource_id FROM release_resources p
                                     WHERE p.tenant_key = r.tenant_key AND p.version_id = r.version_id AND p.parent_id IS NULL
                                       AND p.resource_type = 'template' AND p.resource_key = :parentKey))
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("type", row.type)
        .bind("key", row.key)
        .bind("parentKey", row.parentKey)
        .bind("expected", row.contentHash)
        .bind("hash", contentHash)
        .execute() > 0

    /** A reference [type] [key] of version content holds, by [alias], to [refType] [refKey]. */
    data class Unresolved(val type: String, val key: String, val alias: String?, val refType: String, val refKey: String) {
        override fun toString(): String = "$type $key uses $refType ${alias?.let { "$it:" } ?: ""}$refKey"
    }

    /**
     * The references of [versionId]'s content that resolve to nothing: as the version renders them
     * ([app.epistola.suite.catalog.dependencies.DraftDependencyView]), its own catalog without an alias,
     * else the version its declaration names, the release it pins, or (pinning nothing, or undeclared)
     * the catalog's next version; an image or attribute named without a catalog anywhere in the
     * tenant's current versions (`ResourceReferenceSites`). A variant's references are its template's.
     *
     * The save guard reads this twice per writing transaction, so it is set-based: aliases and the
     * things used are resolved once each, not per reference (`DraftReferenceGuardPerfTest`).
     */
    fun unresolved(handle: Handle, tenantKey: TenantKey, versionId: UUID): Set<Unresolved> = handle
        .createQuery(
            """
            WITH version AS (
                SELECT v.tenant_key, v.catalog_id, v.version_id FROM catalog_versions v WHERE v.tenant_key = :t AND v.version_id = :v
            ),
            -- What the version's content uses, by which alias; a variant's references are its template's.
            refs AS (
                SELECT COALESCE(p.resource_type, r.resource_type) AS type, COALESCE(p.resource_key, r.resource_key) AS key,
                       x.alias, x.resource_type AS ref_type, x.resource_key AS ref_key
                FROM release_resources r
                LEFT JOIN release_resources p ON p.tenant_key = r.tenant_key AND p.version_id = r.version_id AND p.resource_id = r.parent_id AND p.parent_id IS NULL
                JOIN resource_references x ON x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash
                WHERE r.tenant_key = :t AND r.version_id = :v
            ),
            -- The version each alias reads from, resolved once per alias rather than once per reference
            -- (MATERIALIZED, or the planner folds it back into the per-reference join).
            targets AS MATERIALIZED (
                SELECT a.alias, CASE WHEN target_catalog.catalog_id = ver.catalog_id THEN ver.version_id
                                     ELSE COALESCE(
                                         d.target_version_id,
                                         (SELECT tv.version_id FROM catalog_versions tv WHERE tv.tenant_key = ver.tenant_key AND tv.catalog_id = target_catalog.catalog_id
                                            AND tv.state = 'released' AND tv.version = d.release_version),
                                         CASE WHEN d.release_version IS NULL THEN
                                             (SELECT c.next_version_id FROM catalogs c WHERE c.tenant_key = ver.tenant_key AND c.catalog_id = target_catalog.catalog_id)
                                         END)
                                END AS version_id
                FROM (SELECT DISTINCT alias FROM refs) a
                CROSS JOIN version ver
                CROSS JOIN LATERAL (
                    SELECT COALESCE(
                        resolve_catalog_alias(ver.tenant_key, ver.catalog_id, ver.version_id, NULL, a.alias),
                        (SELECT c.catalog_id FROM catalogs c WHERE c.tenant_key = ver.tenant_key AND c.catalog_key = a.alias
                         AND NOT EXISTS (SELECT 1 FROM catalogs o WHERE o.tenant_key = c.tenant_key AND o.catalog_key = c.catalog_key AND o.catalog_id <> c.catalog_id))
                    ) AS catalog_id
                ) target_catalog
                LEFT JOIN catalog_version_dependencies d ON d.tenant_key = ver.tenant_key AND d.version_id = ver.version_id AND d.alias = a.alias
            ),
            -- Each distinct thing used is looked up once, however many resources use it.
            needed AS MATERIALIZED (
                SELECT DISTINCT x.alias, x.ref_type, x.ref_key, t.version_id
                FROM refs x JOIN targets t ON t.alias IS NOT DISTINCT FROM x.alias
            ),
            missing AS MATERIALIZED (
                SELECT n.alias, n.ref_type, n.ref_key FROM needed n
                WHERE NOT EXISTS (
                      SELECT 1 FROM release_resources h
                      WHERE h.tenant_key = :t AND h.version_id = n.version_id AND h.parent_id IS NULL
                        AND h.resource_type = n.ref_type AND h.resource_key = n.ref_key)
                  AND NOT (n.ref_type IN ('image', 'attribute') AND n.alias IS NULL AND EXISTS (
                      SELECT 1 FROM release_resources h
                      JOIN catalog_current_versions cv ON cv.tenant_key = h.tenant_key AND cv.version_id = h.version_id
                      WHERE h.tenant_key = :t AND h.resource_type = n.ref_type AND h.resource_key = n.ref_key))
            )
            SELECT DISTINCT x.type, x.key, x.alias, x.ref_type, x.ref_key
            FROM refs x
            JOIN missing m ON m.alias IS NOT DISTINCT FROM x.alias AND m.ref_type = x.ref_type AND m.ref_key = x.ref_key
            """,
        )
        .bind("t", tenantKey)
        .bind("v", versionId)
        .map { rs, _ -> Unresolved(rs.getString("type"), rs.getString("key"), rs.getString("alias"), rs.getString("ref_type"), rs.getString("ref_key")) }
        .set()
}
