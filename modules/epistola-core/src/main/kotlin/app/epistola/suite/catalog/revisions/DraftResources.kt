// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import java.util.UUID

/**
 * The rows a draft holds of the resource types stored as JSON (ADR 0033): one `release_resources`
 * row per resource, keyed by the draft (`version_id`) where a release's row is keyed by its number,
 * naming its content in `resource_contents`. The one writer of draft rows; reads go through
 * `catalog_version_resources` and the per-type views on it.
 *
 * Every write here names the version its message targets (`versionAt`). The database keeps the
 * rest, as it does for the relational content tables: the identity at the address
 * (`sync_draft_resource_identity`), the revision (`bump_content_revision`), `updated_at`, and the
 * catalog's `content_updated_at` when a row goes.
 */
object DraftResources {

    /**
     * Adds [type] [key] to the targeted draft of [catalog], holding [contentHash], and returns its
     * identity: the one registered at that address, or a new one. A row already there is a
     * unique violation, as an insert into a content table was.
     */
    fun insert(handle: Handle, catalog: CatalogId, type: String, key: String, contentHash: String, name: String, description: String?, actor: UUID?): UUID = handle
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
     * [expectedRevision] (null: whatever it is). False when there is no such row, or it moved on.
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
    ): Boolean = handle
        .createUpdate(
            """
            UPDATE release_resources
            SET content_hash = :hash, name = :name, description = :description, updated_by = :actor
            WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")} AND resource_type = :type AND resource_key = :key
              AND ${Revisions.MATCHES}
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
        .execute() > 0

    /** Removes [type] [key] from the targeted draft of [catalog]; false when it held none. */
    fun delete(handle: Handle, catalog: CatalogId, type: String, key: String): Boolean = handle
        .createUpdate(
            "DELETE FROM release_resources WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")} AND resource_type = :type AND resource_key = :key",
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
            WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")} AND resource_type = :type AND resource_key = :key
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
    fun move(handle: Handle, source: CatalogId, type: String, key: String, target: CatalogId, targetKey: String, contentHash: String): Boolean = handle
        .createUpdate(
            """
            UPDATE release_resources
            SET catalog_id = ${catalogAt("t", "target")}, version_id = ${versionAt("t", "target")}, resource_key = :targetKey, content_hash = :hash
            WHERE tenant_key = :t AND version_id = ${versionAt("t", "source")} AND resource_type = :type AND resource_key = :key
            """,
        )
        .bind("t", source.tenantKey)
        .bindCatalog("source", source)
        .bindCatalog("target", target)
        .bind("type", type)
        .bind("key", key)
        .bind("targetKey", targetKey)
        .bind("hash", contentHash)
        .execute() > 0
}
