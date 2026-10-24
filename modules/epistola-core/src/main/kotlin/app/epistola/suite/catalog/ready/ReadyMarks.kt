// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.ready

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
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
    fun readiness(handle: Handle, catalog: CatalogId): Map<String, Boolean> = handle
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
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .map { rs, _ -> rs.getString("address") to rs.getBoolean("ready") }
        .list()
        .toMap()

    /**
     * The resources of [catalogKey] a merge into the version its callers mean wrote as merged content
     * (`MERGED` in `catalog_draft_merges.resolutions`) that nobody has reviewed since, keyed `"type/key"`.
     *
     * A resource stops awaiting review once every part of it has been marked ready at or after the
     * latest merge that merged it: marking it ready is the review, and so is publishing, which marks
     * it. An edit alone does not end it, since the rest of the merged content is still unreviewed; a
     * mark made before the merge does not count (the merge clears them). A later merge that takes the
     * release's side marks it ready, which ends it too. Catalog settings carry no ready marks and are
     * not listed.
     */
    fun mergedAwaitingReview(handle: Handle, catalog: CatalogId): Set<String> = handle
        .createQuery(
            """
            SELECT merged.address
            FROM (
                SELECT m.version_id, e.key AS address, MAX(m.merged_at) AS merged_at
                FROM catalog_draft_merges m
                CROSS JOIN LATERAL jsonb_each_text(m.resolutions) e
                WHERE m.tenant_key = :t AND m.version_id = ${versionAt("t", "c")} AND e.value = :merged
                GROUP BY m.version_id, e.key
            ) merged
            JOIN catalog_resources r ON r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")}
                 AND r.resource_type || '/' || r.resource_key = merged.address
            WHERE NOT COALESCE((
                SELECT bool_and(mk.marked_at IS NOT NULL AND mk.marked_at >= merged.merged_at)
                FROM resource_content_digests d
                LEFT JOIN resource_ready_marks mk ON mk.tenant_key = d.tenant_key AND mk.version_id = d.version_id
                     AND mk.resource_id = d.resource_id AND mk.part = d.part
                WHERE d.tenant_key = :t AND d.resource_id = r.resource_id AND d.version_id = merged.version_id
            ), FALSE)
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("merged", app.epistola.suite.catalog.merge.MergeResolution.Content.WIRE)
        .mapTo(String::class.java)
        .set()

    /** Whether every part of [resourceId] is ready, in the version its catalog's callers mean (see [versionAt]). */
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

    /**
     * Marks [part] of [resourceId] ready with its content as it is now, in the version its catalog's
     * callers mean (the targeted draft, else the current one); every part when null. A mark belongs to
     * that version: another draft of the catalog keeps its own.
     */
    fun mark(handle: Handle, tenantKey: TenantKey, resourceId: UUID, part: String? = null) {
        handle.createUpdate(
            """
            INSERT INTO resource_ready_marks (tenant_key, version_id, resource_id, part, digest, marked_by)
            SELECT d.tenant_key, d.version_id, d.resource_id, d.part, d.digest, :by
            FROM resource_content_digests d
            WHERE d.tenant_key = :t AND d.resource_id = :id AND ${inCurrentVersion("d")}
              AND (CAST(:part AS TEXT) IS NULL OR d.part = :part)
            ON CONFLICT (tenant_key, version_id, resource_id, part)
            DO UPDATE SET digest = EXCLUDED.digest, marked_at = NOW(), marked_by = EXCLUDED.marked_by
            """,
        )
            .bind("t", tenantKey)
            .bind("id", resourceId)
            .bind("part", part)
            .bind("by", currentUserIdOrNull()?.value)
            .execute()
    }

    /**
     * Removes every mark of [resourceId] in the version its catalog's callers mean (the targeted
     * draft, else the current one), so it is not ready until someone marks it again.
     */
    fun clear(handle: Handle, tenantKey: TenantKey, resourceId: UUID) {
        handle.createUpdate(
            """
            DELETE FROM resource_ready_marks m
            WHERE m.tenant_key = :t AND m.resource_id = :id AND ${inCurrentVersion("m")}
            """,
        )
            .bind("t", tenantKey)
            .bind("id", resourceId)
            .execute()
    }

    /** The identity of the resource of [type] at [key] in [catalogKey], or null when there is none. */
    fun resourceId(handle: Handle, catalog: CatalogId, type: String, key: String): UUID? = handle
        .createQuery(
            """
            SELECT resource_id FROM catalog_resources
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND resource_type = :type AND resource_key = :key
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("type", type)
        .bind("key", key)
        .mapTo(UUID::class.java)
        .findOne()
        .orElse(null)
}
