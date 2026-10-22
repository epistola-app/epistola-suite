// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle

/**
 * A release a release was made from or took in ([version]), named by the [fingerprint] the release
 * recorded for it, when it did.
 */
data class ReleaseParent(val version: String, val fingerprint: String?)

/**
 * One release of a catalog and what it is made of (ADR 0032, contract ADR 0005): its [fingerprint],
 * the release its draft started from ([base]) and the releases merged into that draft before it was
 * released ([merged], its merge parents), each as the release recorded it.
 */
data class LineageRelease(
    val version: String,
    val fingerprint: String,
    val base: ReleaseParent?,
    val merged: List<ReleaseParent> = emptyList(),
) {
    /** Its parents: its base first, then what it merged, in the order recorded. */
    val parents: List<ReleaseParent> get() = listOfNotNull(base) + merged
}

/**
 * The lineage of a catalog's releases: what each release contains, by what it records (its base and
 * its merge parents), never guessed from the numbers. A release that records neither ends its line.
 */
class ReleaseLineage(val releases: Map<String, LineageRelease>) {

    /** [release]'s parents, base first; empty for one that records none, or one this catalog lacks. */
    fun parentsOf(release: String): List<ReleaseParent> = releases[release]?.parents.orEmpty()

    /**
     * Every release a line made of [roots] contains: each root and, recursively, every parent of
     * every release in it. For a draft the roots are its base and the releases it merged.
     */
    fun contained(roots: Collection<String>): Set<String> {
        val seen = linkedSetOf<String>()
        val todo = ArrayDeque(roots)
        while (todo.isNotEmpty()) {
            val release = todo.removeFirst()
            if (seen.add(release)) parentsOf(release).forEach { todo.addLast(it.version) }
        }
        return seen
    }

    /** [contained] for a draft on [base] that merged [merged]. */
    fun containedBy(base: String?, merged: Collection<String>): Set<String> = contained(listOfNotNull(base) + merged)

    /**
     * The closest release on [release]'s line (itself, then its parents breadth first, base before
     * merges) that [contained] holds, with the fingerprint the release it was reached from named it
     * by (null for [release] itself and for a link recorded without one). Null when none is.
     */
    fun closestIn(release: String, contained: Set<String>): ReleaseParent? {
        val seen = mutableSetOf<String>()
        val todo = ArrayDeque(listOf(ReleaseParent(release, null)))
        while (todo.isNotEmpty()) {
            val current = todo.removeFirst()
            if (!seen.add(current.version)) continue
            if (current.version in contained) return current
            parentsOf(current.version).forEach(todo::addLast)
        }
        return null
    }

    /** Whether [release] is [ancestor] or descends from it, through any parent. */
    fun descendsFrom(release: String, ancestor: String): Boolean = ancestor in contained(listOf(release))

    /**
     * The releases a draft on [base] that merged [merged] is behind when it is released as [number]
     * (contract ADR 0002, "A draft can be behind"; ADR 0032), lowest first: every release of the
     * catalog numbered below [number] that its line does not contain ([containedBy]).
     *
     * A release on the line that records no lineage (one received from an archive that named no
     * base, or cut before releases recorded it) cannot say what it contains; it is taken to contain
     * every release numbered below it, as releases cut one after another did. That is the only place
     * a number stands in for lineage, and only for what is listed as behind, never for a merge base.
     */
    fun behind(base: String?, merged: Collection<String>, number: String): List<String> {
        val ceiling = SemVer.parseOrNull(number) ?: return emptyList()
        val line = containedBy(base, merged)
        val unrecorded = line.filter { releases[it]?.parents.isNullOrEmpty() }.mapNotNull(SemVer::parseOrNull)
        return releases.keys.mapNotNull { r -> SemVer.parseOrNull(r)?.let { it to r } }
            .filter { (semVer, r) -> semVer < ceiling && r !in line && unrecorded.none { semVer < it } }
            .sortedBy { it.first }
            .map { it.second }
    }

    companion object {
        /** The lineage of [catalogKey]'s releases. */
        fun read(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): ReleaseLineage {
            val merges = handle.createQuery(
                """
                SELECT version, merged_version, merged_fingerprint FROM catalog_release_merges
                WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} ORDER BY version, merged_version
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .map { rs, _ -> rs.getString("version") to ReleaseParent(rs.getString("merged_version"), rs.getString("merged_fingerprint")) }
                .list()
                .groupBy({ it.first }, { it.second })
            val releases = handle.createQuery(
                "SELECT version, fingerprint, base_version, base_fingerprint FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}",
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .map { rs, _ ->
                    val version = rs.getString("version")
                    LineageRelease(
                        version = version,
                        fingerprint = rs.getString("fingerprint"),
                        base = rs.getString("base_version")?.let { ReleaseParent(it, rs.getString("base_fingerprint")) },
                        merged = merges[version].orEmpty(),
                    )
                }
                .list()
            return ReleaseLineage(releases.associateBy { it.version })
        }

        /** The merge parents release [version] of [catalogKey] records, lowest first. */
        fun mergedOf(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): List<ReleaseParent> = handle
            .createQuery(
                """
                SELECT merged_version, merged_fingerprint FROM catalog_release_merges
                WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v
                """,
            )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("v", version)
            .map { rs, _ -> ReleaseParent(rs.getString("merged_version"), rs.getString("merged_fingerprint")) }
            .list()
            .sortedBy { SemVer.parseOrNull(it.version) }

        /**
         * Records [merged] as the merge parents of release [version] of [catalogKey]. A parent
         * recorded already stays as it was.
         */
        fun recordMerged(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, merged: Collection<ReleaseParent>) {
            if (merged.isEmpty()) return
            val batch = handle.prepareBatch(
                """
                INSERT INTO catalog_release_merges (tenant_key, catalog_id, version, merged_version, merged_fingerprint)
                VALUES (:t, ${catalogAt("t", "c")}, :version, :merged, :fingerprint)
                ON CONFLICT DO NOTHING
                """,
            )
            merged.distinctBy { it.version }.forEach { parent ->
                batch.bind("t", tenantKey).bind("c", catalogKey).bind("version", version)
                    .bind("merged", parent.version).bind("fingerprint", parent.fingerprint).add()
            }
            batch.execute()
        }
    }
}
