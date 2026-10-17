// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.ready

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.currentUserIdOrNull
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Ready marks (contract ADR 0002): that a resource's content was reviewed and may be released.
 *
 * A mark copies the content digest the database computes for the resource (view
 * `resource_content_digests`), one per part: a template has a part per variant, every other resource
 * one. A part is ready while its mark equals its current digest, so an edit makes it stale without
 * anything clearing it, and a resource is ready when all its parts are.
 */
@Component
class ReadyMarks {

    /** Whether each resource of [catalogKey] is ready, keyed `"type/key"` as catalog content is. */
    fun readiness(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, Boolean> = handle
        .createQuery(
            """
            SELECT r.resource_type || '/' || r.resource_key AS address,
                   bool_and(m.digest IS NOT NULL AND m.digest = d.digest) AS ready
            FROM catalog_resources r
            JOIN resource_content_digests d ON d.tenant_key = r.tenant_key AND d.resource_id = r.resource_id
                 AND d.version_id = ${versionAt("t", "c")}
            LEFT JOIN resource_ready_marks m ON m.tenant_key = d.tenant_key AND m.version_id = d.version_id
                 AND m.resource_id = d.resource_id AND m.part = d.part
            WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")}
            GROUP BY 1
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map { rs, _ -> rs.getString("address") to rs.getBoolean("ready") }
        .list()
        .toMap()

    /** Whether every part of [resourceId] is ready. */
    fun isReady(handle: Handle, tenantKey: TenantKey, resourceId: UUID): Boolean = handle
        .createQuery(
            """
            SELECT COALESCE(bool_and(m.digest IS NOT NULL AND m.digest = d.digest), FALSE)
            FROM resource_content_digests d
            LEFT JOIN resource_ready_marks m ON m.tenant_key = d.tenant_key AND m.version_id = d.version_id
                 AND m.resource_id = d.resource_id AND m.part = d.part
            -- The resource's own catalog's current version: a resource lives in one catalog.
            WHERE d.tenant_key = :t AND d.resource_id = :id AND ${inCurrentVersion("d")}
            """,
        )
        .bind("t", tenantKey)
        .bind("id", resourceId)
        .mapTo(Boolean::class.java)
        .one()

    /** Marks [part] of [resourceId] ready with its content as it is now; every part when null. */
    fun mark(handle: Handle, tenantKey: TenantKey, resourceId: UUID, part: String? = null) {
        handle.createUpdate(
            """
            INSERT INTO resource_ready_marks (tenant_key, version_id, resource_id, part, digest, marked_by)
            SELECT d.tenant_key, d.version_id, d.resource_id, d.part, d.digest, :by
            FROM resource_content_digests d
            WHERE d.tenant_key = :t AND d.resource_id = :id AND ${inCurrentVersion("d")}
              AND (CAST(:part AS TEXT) IS NULL OR d.part = :part)
            ON CONFLICT (tenant_key, resource_id, part)
            DO UPDATE SET version_id = EXCLUDED.version_id, digest = EXCLUDED.digest, marked_at = NOW(), marked_by = EXCLUDED.marked_by
            """,
        )
            .bind("t", tenantKey)
            .bind("id", resourceId)
            .bind("part", part)
            .bind("by", currentUserIdOrNull()?.value)
            .execute()
    }

    /** The identity of the resource of [type] at [key] in [catalogKey], or null when there is none. */
    fun resourceId(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, type: String, key: String): UUID? = handle
        .createQuery(
            """
            SELECT resource_id FROM catalog_resources
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND resource_type = :type AND resource_key = :key
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("type", type)
        .bind("key", key)
        .mapTo(UUID::class.java)
        .findOne()
        .orElse(null)
}
