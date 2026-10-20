// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.queries.LATEST_RELEASE_ORDER
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import java.util.UUID

/** One resource as a release holds it. */
data class ReleaseEntry(
    val resourceType: String,
    val resourceKey: String,
    val resourceId: UUID,
    val revisionDigest: String,
    val fingerprint: String,
    val name: String,
    val description: String?,
) {
    /** How catalog content keys a resource: `"type/slug"`. */
    val contentKey: String get() = "$resourceType/$resourceKey"
}

/**
 * What a release contains, resource by resource.
 *
 * Sole owner of the `release_entries` SQL. Written inside the release transaction, beside the
 * revisions it points at, so a release never exists without the record of what it held.
 *
 * Each entry carries **both** digests, because they answer different questions and neither can be
 * derived from the other (ADR 0026 §5): `revisionDigest` is where the content is stored, and
 * `fingerprint` is the contract's canonical digest over the wire form, which is what the working
 * copy is compared against to say a resource has changed.
 */
@Component
class ReleaseEntryStore {

    /**
     * Records the resources of a release.
     *
     * @param revisionDigests each resource's revision, keyed `"type/slug"`, from [ResourceRevisionStore].
     * @param fingerprints each resource's canonical wire digest, keyed the same way.
     */
    fun record(
        handle: Handle,
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        version: String,
        content: CatalogContent,
        revisionDigests: Map<String, String>,
        fingerprints: Map<String, String>,
    ) {
        val identities = loadIdentities(handle, tenantKey, catalogKey)

        for (entry in content.resourceEntries) {
            val key = "${entry.type}/${entry.key}"
            // Every catalog resource is in the identity registry — the sync trigger puts it there
            // on insert, and V20260905090000 backfilled the rest. A gap is a defect in that
            // invariant, and a release is exactly where it should be noticed rather than recorded
            // as a null nobody can trace later.
            val resourceId = requireNotNull(identities[key]) {
                "No identity registered for $key in catalog ${catalogKey.value}; cannot record it in a release"
            }
            handle.createUpdate(
                """
                INSERT INTO release_entries (
                    tenant_key, catalog_id, version, resource_type, resource_key,
                    resource_id, revision_digest, fingerprint, name, description
                )
                VALUES (:t, ${catalogAt("t", "c")}, :version, :type, :key, :resourceId, :revision, :fingerprint, :name, :description)
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("type", entry.type)
                .bind("key", entry.key)
                .bind("resourceId", resourceId)
                .bind("revision", requireNotNull(revisionDigests[key]) { "No revision retained for $key" })
                .bind("fingerprint", requireNotNull(fingerprints[key]) { "No fingerprint computed for $key" })
                .bind("name", entry.name)
                .bind("description", entry.description)
                .execute()
        }
    }

    /** The entries of one release, in the order a manifest lists them. */
    fun entriesOf(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): List<ReleaseEntry> = handle
        .createQuery(
            """
            SELECT resource_type, resource_key, resource_id, revision_digest, fingerprint, name, description
            FROM release_entries
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :version
            ORDER BY resource_type, resource_key
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("version", version)
        .map { rs, _ ->
            ReleaseEntry(
                resourceType = rs.getString("resource_type"),
                resourceKey = rs.getString("resource_key"),
                resourceId = rs.getObject("resource_id", UUID::class.java),
                revisionDigest = rs.getString("revision_digest"),
                fingerprint = rs.getString("fingerprint"),
                name = rs.getString("name"),
                description = rs.getString("description"),
            )
        }
        .list()

    /** The released versions of a catalog, newest first. */
    fun versions(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): List<String> = handle
        .createQuery(
            """
            SELECT r.version
            FROM catalog_releases r
            WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")}
            $LATEST_RELEASE_ORDER
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .mapTo(String::class.java)
        .list()

    /** The latest release of [catalogKey], or null when it has none. */
    fun latestRelease(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? = handle
        .createQuery(
            """
            SELECT version FROM catalog_releases
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            $LATEST_RELEASE_ORDER
            LIMIT 1
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)

    /** Every catalog of the tenant that has a release, with its latest release. */
    fun latestReleases(handle: Handle, tenantKey: TenantKey): Map<CatalogKey, String> = handle
        .createQuery(
            """
            SELECT DISTINCT ON (cat.catalog_key) cat.catalog_key, r.version FROM catalog_releases r
            JOIN catalogs cat ON cat.tenant_key = r.tenant_key AND cat.catalog_id = r.catalog_id
            WHERE r.tenant_key = :t
            ORDER BY cat.catalog_key, r.version_major DESC NULLS LAST, r.version_minor DESC NULLS LAST,
                     r.version_patch DESC NULLS LAST, r.released_at DESC
            """,
        )
        .bind("t", tenantKey)
        .map { rs, _ -> CatalogKey.of(rs.getString("catalog_key")) to rs.getString("version") }
        .list()
        .toMap()

    private fun loadIdentities(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, UUID> = handle
        .createQuery(
            """
            SELECT resource_type, resource_key, resource_id
            FROM catalog_resources
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map { rs, _ ->
            "${rs.getString("resource_type")}/${rs.getString("resource_key")}" to rs.getObject("resource_id", UUID::class.java)
        }
        .list()
        .toMap()
}

/** The manifest entry a release entry describes, as [CatalogContent] carries it. */
fun ReleaseEntry.toResourceEntry(): ResourceEntry = ResourceEntry(
    type = resourceType,
    key = resourceKey,
    name = name,
    description = description,
    detailUrl = "./resources/$resourceType/$resourceKey.json",
)
