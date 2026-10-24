// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.queries.LATEST_RELEASE_ORDER
import app.epistola.suite.common.ids.CatalogId
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
     * @param registerNew whether a resource the catalog never held gets an identity here: a release
     *   received beside the others (ADR 0032) may bring one that no version of the catalog has. A
     *   release cut here holds only what a version held, so there a gap is a defect.
     */
    fun record(
        handle: Handle,
        catalog: CatalogId,
        version: String,
        content: CatalogContent,
        revisionDigests: Map<String, String>,
        fingerprints: Map<String, String>,
        registerNew: Boolean = false,
    ) {
        val identities = loadIdentities(handle, catalog).toMutableMap()
        if (registerNew) {
            content.resourceEntries.filter { "${it.type}/${it.key}" !in identities }.forEach { entry ->
                identities["${entry.type}/${entry.key}"] = register(handle, catalog, entry.type, entry.key)
            }
        }

        for (entry in content.resourceEntries) {
            val key = "${entry.type}/${entry.key}"
            // Every catalog resource is in the identity registry — the sync trigger puts it there
            // on insert, and V20260905090000 backfilled the rest. A gap is a defect in that
            // invariant, and a release is exactly where it should be noticed rather than recorded
            // as a null nobody can trace later.
            val resourceId = requireNotNull(identities[key]) {
                "No identity registered for $key in catalog ${catalog.key.value}; cannot record it in a release"
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
                .bind("t", catalog.tenantKey)
                .bindCatalog("c", catalog)
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
    fun entriesOf(handle: Handle, catalog: CatalogId, version: String): List<ReleaseEntry> = handle
        .createQuery(
            """
            SELECT resource_type, resource_key, resource_id, revision_digest, fingerprint, name, description
            FROM release_entries
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :version
            ORDER BY resource_type, resource_key
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
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
    fun versions(handle: Handle, catalog: CatalogId): List<String> = handle
        .createQuery(
            """
            SELECT r.version
            FROM catalog_releases r
            WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")}
            $LATEST_RELEASE_ORDER
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .mapTo(String::class.java)
        .list()

    /** The latest release of [catalogKey], or null when it has none. */
    fun latestRelease(handle: Handle, catalog: CatalogId): String? = handle
        .createQuery(
            """
            SELECT version FROM catalog_releases
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            $LATEST_RELEASE_ORDER
            LIMIT 1
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)

    /** Every catalog of the tenant that has a release, with its latest release. */
    fun latestReleases(handle: Handle, tenantKey: TenantKey): Map<CatalogId, String> = handle
        .createQuery(
            """
            SELECT DISTINCT ON (r.catalog_id) cat.namespace, cat.catalog_key, r.version FROM catalog_releases r
            JOIN catalogs cat ON cat.tenant_key = r.tenant_key AND cat.catalog_id = r.catalog_id
            WHERE r.tenant_key = :t
            ORDER BY r.catalog_id, ${app.epistola.suite.catalog.queries.releaseNumberOrder("r")}
            """,
        )
        .bind("t", tenantKey)
        .map { rs, _ -> CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key")), app.epistola.suite.common.ids.TenantId(tenantKey)) to rs.getString("version") }
        .list()
        .toMap()

    /** A new identity at [type]/[key] of [catalogKey], which a release holds and no version does. */
    private fun register(handle: Handle, catalog: CatalogId, type: String, key: String): UUID = handle
        .createQuery(
            """
            INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_id, resource_key)
            VALUES (:t, epistola_uuidv7(), :type, ${catalogAt("t", "c")}, :key)
            RETURNING resource_id
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("type", type)
        .bind("key", key)
        .mapTo(UUID::class.java)
        .one()

    private fun loadIdentities(handle: Handle, catalog: CatalogId): Map<String, UUID> = handle
        .createQuery(
            """
            SELECT resource_type, resource_key, resource_id
            FROM catalog_resources
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
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
