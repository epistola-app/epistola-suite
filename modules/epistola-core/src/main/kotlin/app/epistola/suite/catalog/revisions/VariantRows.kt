// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.requireWritableVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.writable
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A template's variants as rows of their own in `release_resources` (ADR 0033, step 7b): storage
 * type [TYPE], each naming its template's row (`parent_id`, the template's identity) in the same
 * draft or release, with its own content, revision and bookkeeping. The one writer of variant rows.
 * Every draft write names the version its message targets (`versionAt`); the database keeps a row's
 * revision and `updated_at`, checks it has its template's row, and deletes it with that row.
 */
object VariantRows {
    /** The storage type of a variant row; not a wire type, so never in `catalog_resources`. */
    const val TYPE = "variant"

    /** A variant row of a release: its key, content, title and description. */
    data class Released(val key: String, val contentHash: String, val title: String, val description: String?)

    /** Adds variant [key] of template [parentId] to the targeted draft of [catalog], holding [contentHash]. */
    fun insert(handle: Handle, catalog: CatalogId, parentId: UUID, key: String, contentHash: String, title: String, description: String?, actor: UUID?) {
        handle.requireWritableVersion(catalog)
        handle.createUpdate(
            """
            INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id, parent_id,
                content_hash, name, description, created_by, updated_by)
            VALUES (:t, ${catalogAt("t", "c")}, ${versionAt("t", "c")}, '$TYPE', :key, variant_resource_id(:parent, :key), :parent,
                :hash, :name, :description, :actor, :actor)
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .bind("parent", parentId)
            .bind("key", key)
            .bind("hash", contentHash)
            .bind("name", title)
            .bind("description", description)
            .bind("actor", actor)
            .execute()
    }

    /**
     * Points variant [key] of template [parentId] in the targeted draft at [contentHash], when its
     * revision is still [expectedRevision] (null: whatever it is). Null when there is no such variant,
     * or it moved on; else its revision and `updated_at` after the change.
     */
    fun update(
        handle: Handle,
        catalog: CatalogId,
        parentId: UUID,
        key: String,
        contentHash: String,
        title: String,
        description: String?,
        actor: UUID?,
        expectedRevision: Long? = null,
    ): DraftResources.Updated? = handle.writable(catalog)
        .createQuery(
            """
            UPDATE release_resources
            SET content_hash = :hash, name = :name, description = :description, updated_by = :actor
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version_id = ${versionAt("t", "c")}
              AND resource_type = '$TYPE' AND resource_key = :key AND parent_id = :parent AND ${Revisions.MATCHES}
            RETURNING revision, updated_at
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("parent", parentId)
        .bind("key", key)
        .bind("hash", contentHash)
        .bind("name", title)
        .bind("description", description)
        .bind("actor", actor)
        .bind("expectedRevision", expectedRevision)
        .map { rs, _ -> DraftResources.Updated(rs.getLong("revision"), rs.getObject("updated_at", OffsetDateTime::class.java)) }
        .findOne()
        .orElse(null)

    /** Removes variant [key] of template [parentId] from the targeted draft; false when there was none. */
    fun delete(handle: Handle, catalog: CatalogId, parentId: UUID, key: String): Boolean = handle.writable(catalog)
        .createUpdate(
            """
            DELETE FROM release_resources
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version_id = ${versionAt("t", "c")}
              AND resource_type = '$TYPE' AND resource_key = :key AND parent_id = :parent
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("parent", parentId)
        .bind("key", key)
        .execute() > 0

    /**
     * The revision of variant [key] of template [templateKey] in the targeted draft, locking that one
     * row until the transaction ends (not its template's, nor its other variants'); null when there is
     * none.
     */
    fun lockRevision(handle: Handle, catalog: CatalogId, templateKey: String, key: String): Long? = handle
        .createQuery(
            """
            SELECT v.revision FROM release_resources v
            WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.version_id = ${versionAt("t", "c")}
              AND v.resource_type = '$TYPE' AND v.resource_key = :key
              AND v.parent_id = (
                  SELECT t.resource_id FROM release_resources t
                  WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")} AND t.version_id = ${versionAt("t", "c")}
                    AND t.resource_type = 'template' AND t.resource_key = :template AND t.parent_id IS NULL
              )
            FOR UPDATE OF v
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("template", templateKey)
        .bind("key", key)
        .mapTo(Long::class.java)
        .findOne()
        .orElse(null)

    /**
     * Moves the variants of template [parentId] from the targeted draft of [source] to [target]'s,
     * as its row moved (`DraftResources.move`): relocation moves them, no cascade does (ADR 0014),
     * and the database refuses at commit a template moved without them.
     */
    fun move(handle: Handle, source: CatalogId, parentId: UUID, target: CatalogId) {
        handle.writable(source).requireWritableVersion(target)
        handle.createUpdate(
            """
            UPDATE release_resources
            SET catalog_id = ${catalogAt("t", "target")}, version_id = ${versionAt("t", "target")}
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "source")} AND version_id = ${versionAt("t", "source")}
              AND resource_type = '$TYPE' AND parent_id = :parent
            """,
        )
            .bind("t", source.tenantKey)
            .bindCatalog("source", source)
            .bindCatalog("target", target)
            .bind("parent", parentId)
            .execute()
    }

    /** A variant row as its template's store reads it: its own fields, and the content it names. */
    data class Row(val key: String, val title: String, val description: String?, val contentHash: String)

    /**
     * The variant rows of template [parentId] in the draft [versionId] or the release [version] its row
     * is of (as `current_templates` gives them), by key: one indexed read of that template's rows.
     */
    fun of(handle: Handle, tenantKey: app.epistola.suite.common.ids.TenantKey, parentId: UUID, versionId: UUID?, version: String?): Map<String, Row> = handle
        .createQuery(
            """
            SELECT v.resource_key, v.name, v.description, v.content_hash
            FROM release_resources v
            WHERE v.tenant_key = :t AND v.parent_id = :parent AND v.resource_type = '$TYPE'
              AND ${if (versionId != null) "v.version_id = :versionId" else "v.version_id = (SELECT rv.version_id FROM catalog_versions rv WHERE rv.tenant_key = v.tenant_key AND rv.catalog_id = v.catalog_id AND rv.version = :version AND rv.state = 'released')"}
            """,
        )
        .bind("t", tenantKey)
        .bind("parent", parentId)
        .apply { if (versionId != null) bind("versionId", versionId) else bind("version", version) }
        .map { rs, _ -> Row(rs.getString("resource_key"), rs.getString("name"), rs.getString("description"), rs.getString("content_hash")) }
        .list()
        .associateBy { it.key }

    /**
     * Records [variants] of template [parentId] in the release whose row is [versionId], frozen with it:
     * a frozen draft's variant rows become the release's as they are, and one it left out goes.
     */
    fun recordRelease(handle: Handle, catalog: CatalogId, versionId: UUID, parentId: UUID, variants: List<Released>) {
        if (variants.isNotEmpty()) {
            val batch = handle.prepareBatch(
                """
                INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id, parent_id,
                    content_hash, name, description)
                VALUES (:t, ${catalogAt("t", "c")}, :versionId, '$TYPE', :key, variant_resource_id(:parent, :key), :parent, :hash, :name, :description)
                ON CONFLICT (tenant_key, version_id, parent_id, resource_key)
                DO UPDATE SET content_hash = EXCLUDED.content_hash, name = EXCLUDED.name, description = EXCLUDED.description
                """,
            )
            variants.forEach { variant ->
                batch.bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("versionId", versionId).bind("parent", parentId)
                    .bind("key", variant.key).bind("hash", variant.contentHash).bind("name", variant.title).bind("description", variant.description).add()
            }
            batch.execute()
        }
        handle.createUpdate(
            "DELETE FROM release_resources WHERE tenant_key = :t AND version_id = :v AND parent_id = :parent AND resource_type = '$TYPE' AND NOT (resource_key = ANY(:keys))",
        )
            .bind("t", catalog.tenantKey)
            .bind("v", versionId)
            .bind("parent", parentId)
            .bindArray("keys", String::class.java, variants.map { it.key })
            .execute()
    }

    /** The content of each variant of template [parentId] in release [version] of [catalog], by key. */
    fun releasedHashes(handle: Handle, catalog: CatalogId, version: String, parentId: UUID): Map<String, String> = handle
        .createQuery(
            """
            SELECT v.resource_key, v.content_hash FROM release_resources v
            JOIN catalog_versions rv ON rv.tenant_key = v.tenant_key AND rv.version_id = v.version_id
            WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND rv.version = :version AND rv.state = 'released'
              AND v.resource_type = '$TYPE' AND v.parent_id = :parent
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("version", version)
        .bind("parent", parentId)
        .map { rs, _ -> rs.getString("resource_key") to rs.getString("content_hash") }
        .list()
        .toMap()
}
