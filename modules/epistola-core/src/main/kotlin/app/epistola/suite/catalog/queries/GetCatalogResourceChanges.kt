// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * What the next release of an AUTHORED catalog would contain that the last one did not — resource
 * by resource.
 *
 * [GetCatalogReleaseStatus] answers *whether* the working copy has drifted from the release; this
 * answers *where*. Both compare the same canonical content, so they cannot disagree: a per-resource
 * digest difference is exactly a catalog fingerprint difference localized to one resource.
 *
 * O(catalog-size), like the drift check it refines — it builds the working copy once. Read it for a
 * page about one catalog, not for a list of them.
 */
data class GetCatalogResourceChanges(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogResourceChanges>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** Where one resource of the working copy stands relative to the catalog's last release. */
enum class CatalogResourceState {
    /** In the working copy and not in the last release. */
    NEW,

    /** In both, with different content. */
    MODIFIED,

    /** In both, byte-identical. */
    RELEASED,

    /** In the last release and no longer in the working copy. */
    REMOVED,

    /** Changed or not — the last release recorded no per-resource baseline to compare against. */
    UNKNOWN,
}

data class CatalogResourceChange(
    val type: String,
    val slug: String,
    /** The working copy's name; null for a [CatalogResourceState.REMOVED] resource, which has none. */
    val name: String?,
    val state: CatalogResourceState,
)

data class CatalogResourceChanges(
    val catalogKey: CatalogKey,
    val latestVersion: String?,
    /**
     * False when the catalog has drifted from a release cut before per-resource digests were
     * recorded (V20260923154857), which cannot be reconstructed. Every resource is then
     * [CatalogResourceState.UNKNOWN]; the next release restores the detail.
     */
    val baselineAvailable: Boolean,
    /** Ordered by type then slug, the order the manifest lists them in. */
    val resources: List<CatalogResourceChange>,
) {
    val added: List<CatalogResourceChange> get() = inState(CatalogResourceState.NEW)
    val modified: List<CatalogResourceChange> get() = inState(CatalogResourceState.MODIFIED)
    val removed: List<CatalogResourceChange> get() = inState(CatalogResourceState.REMOVED)
    val released: List<CatalogResourceChange> get() = inState(CatalogResourceState.RELEASED)

    val hasUnreleasedChanges: Boolean get() = resources.any { it.state != CatalogResourceState.RELEASED }

    private fun inState(state: CatalogResourceState) = resources.filter { it.state == state }
}

@Component
class GetCatalogResourceChangesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
) : QueryHandler<GetCatalogResourceChanges, CatalogResourceChanges> {

    override fun handle(query: GetCatalogResourceChanges): CatalogResourceChanges {
        val catalog = GetCatalog(query.tenantKey, query.catalogKey).query()
            ?: throw CatalogNotFoundException(query.catalogKey)
        // Only an authored catalog has a next release. A subscribed one is replaced wholesale by
        // its source, and `PreviewCatalogUpgrade` is the diff that applies to it.
        if (catalog.type != CatalogType.AUTHORED) throw CatalogReadOnlyException(query.catalogKey)

        val content = contentBuilder.build(query.tenantKey, query.catalogKey)
        val working = fingerprintService.perResourceFingerprints(content)
        val names = content.resourceEntries.associate { "${it.type}/${it.slug}" to it.name }
        val release = loadLatestRelease(query.tenantKey, query.catalogKey)

        fun change(key: String, state: CatalogResourceState) = CatalogResourceChange(
            type = key.substringBefore('/'),
            slug = key.substringAfter('/'),
            name = names[key],
            state = state,
        )

        // Nothing released yet: every resource is new, and that is a complete answer.
        if (release == null) {
            return CatalogResourceChanges(
                catalogKey = query.catalogKey,
                latestVersion = null,
                baselineAvailable = true,
                resources = working.keys.sorted().map { change(it, CatalogResourceState.NEW) },
            )
        }

        val baseline = release.resourceFingerprints
            // A release cut before V20260923154857 recorded no baseline. When the working copy
            // still matches its fingerprint the content is identical by definition, so the working
            // digests *are* that release's digests and the answer is exact without one.
            ?: if (fingerprintService.matchesFingerprint(content, release.fingerprint)) {
                working
            } else {
                return CatalogResourceChanges(
                    catalogKey = query.catalogKey,
                    latestVersion = release.version,
                    baselineAvailable = false,
                    resources = working.keys.sorted().map { change(it, CatalogResourceState.UNKNOWN) },
                )
            }

        val resources = (working.keys + baseline.keys).sorted().map { key ->
            val state = when {
                key !in baseline -> CatalogResourceState.NEW
                key !in working -> CatalogResourceState.REMOVED
                working[key] != baseline[key] -> CatalogResourceState.MODIFIED
                else -> CatalogResourceState.RELEASED
            }
            change(key, state)
        }

        return CatalogResourceChanges(
            catalogKey = query.catalogKey,
            latestVersion = release.version,
            baselineAvailable = true,
            resources = resources,
        )
    }

    private data class ReleaseRow(
        val version: String,
        val fingerprint: String,
        val resourceFingerprints: Map<String, String>?,
    )

    private fun loadLatestRelease(tenantKey: TenantKey, catalogKey: CatalogKey): ReleaseRow? = jdbi.withHandle<ReleaseRow?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT version, fingerprint, resource_fingerprints
            FROM catalog_releases
            WHERE tenant_key = :t AND catalog_key = :c
            $LATEST_RELEASE_ORDER
            LIMIT 1
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ ->
                ReleaseRow(
                    version = rs.getString("version"),
                    fingerprint = rs.getString("fingerprint"),
                    resourceFingerprints = rs.getString("resource_fingerprints")?.let { json ->
                        @Suppress("UNCHECKED_CAST")
                        objectMapper.readValue(json, Map::class.java) as Map<String, String>
                    },
                )
            }
            .findOne()
            .orElse(null)
    }
}
