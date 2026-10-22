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
 * recorded for it, when it did: its content hash (ADR 0034), or for a parent recorded before, the
 * fingerprint it had then.
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
    /** Its content hash (ADR 0034), when recorded. */
    val contentHash: String? = null,
) {
    /** Its parents: its base first, then what it merged, in the order recorded. */
    val parents: List<ReleaseParent> get() = listOfNotNull(base) + merged
}

/**
 * The lineage of a catalog's releases: what each release contains, by what it records (its base and
 * its merge parents), never guessed from the numbers. A release that records neither ends its line.
 *
 * Lineage is by hash (ADR 0034): a parent named by a fingerprint is the release of this catalog
 * recorded with that fingerprint or content hash, whatever number it has here, so a line survives a
 * release received under another number. A parent whose fingerprint names no release here is its
 * number, as it was recorded ([closestIn] then finds it missing or another release).
 */
class ReleaseLineage(releases: Map<String, LineageRelease>) {
    val releases: Map<String, LineageRelease> = byHash(releases)

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

    /**
     * The line a draft on [base] that merged [merged] continues, and where each release it is
     * [behind] (as [ReleaseLineage.behind] lists them) was made off it, for drawing ([BehindLine]).
     *
     * The line is the draft's first parents (its base, that release's base, …), cut at the oldest one
     * a release it lacks was made on: directly, through another release it lacks that was made on
     * it, or through a release merged into the line, which counts where it came in. Releases merged
     * into a release on that stretch, or into the draft, are joined to it; so is a release merged in
     * further back that a lacking one was made on, where it came into the line.
     */
    fun behindLine(base: String?, merged: Collection<String>, behind: List<String>): BehindLine {
        // The draft's first parents, newest first. Bounded by what was seen, so a loop cannot run on.
        val firstParents = mutableListOf<String>()
        var at = base
        while (at != null && at in releases && at !in firstParents) {
            firstParents += at
            at = releases.getValue(at).base?.version
        }
        val lacking = behind.toSet()
        val held = containedBy(base, merged)
        val heldAt = firstParents.associateWith { contained(listOf(it)) }
        fun baseOf(release: String): String? = releases[release]?.base?.version

        // Where a release the draft holds came into its line: the oldest release on it that holds it
        // (each holds what the ones before it do), or null when only the draft's own merges bring it.
        fun cameIn(release: String): String? = firstParents.lastOrNull { release in heldAt.getValue(it) }

        // The release on the line a lacking one hangs off, following the lacking ones it was made on.
        fun anchor(release: String, seen: Set<String>): String? {
            val madeOn = baseOf(release) ?: return null
            return when {
                madeOn in firstParents -> madeOn
                madeOn in lacking && madeOn !in seen -> anchor(madeOn, seen + release)
                madeOn in held -> cameIn(madeOn)
                else -> null
            }
        }
        val anchors = behind.mapNotNull { anchor(it, emptySet()) }.toSet()
        val oldest = firstParents.indices.lastOrNull { firstParents[it] in anchors } ?: 0
        val line = firstParents.take(oldest + 1).reversed()
        val onLine = line.toSet()

        val joined = linkedMapOf<String, String?>()
        fun join(release: String, into: String?) {
            if (release in releases && release !in firstParents && release !in lacking) joined.putIfAbsent(release, into)
        }
        line.forEach { release -> releases.getValue(release).merged.forEach { join(it.version, release) } }
        merged.forEach { join(it, cameIn(it)) }
        behind.forEach { release -> baseOf(release)?.takeIf { it in held }?.let { join(it, cameIn(it)) } }

        val drawn = onLine + joined.keys + lacking
        return BehindLine(
            line = line,
            joined = joined.entries
                .sortedWith(compareBy(nullsLast()) { SemVer.parseOrNull(it.key) })
                .map { (release, into) -> JoinedRelease(release, into) },
            behind = behind.map { release -> BehindBranch(release, baseOf(release)?.takeIf { it in drawn }) },
        )
    }

    companion object {
        /**
         * [releases] with every parent named by a fingerprint that the release of its number here does
         * not have resolved to the release here that has it, by fingerprint or content hash. Several
         * numbers may name one content (a notes-only re-release); a parent whose number has it stays.
         */
        private fun byHash(releases: Map<String, LineageRelease>): Map<String, LineageRelease> {
            val named = mutableMapOf<String, String>()
            releases.values.sortedWith(compareBy(nullsLast()) { SemVer.parseOrNull(it.version) }).forEach { r ->
                named.putIfAbsent(r.fingerprint, r.version)
                r.contentHash?.let { named.putIfAbsent(it, r.version) }
            }
            fun local(parent: ReleaseParent): ReleaseParent {
                val fingerprint = parent.fingerprint ?: return parent
                val atNumber = releases[parent.version]
                if (atNumber != null && (atNumber.fingerprint == fingerprint || atNumber.contentHash == fingerprint)) return parent
                return named[fingerprint]?.let { ReleaseParent(it, fingerprint) } ?: parent
            }
            return releases.mapValues { (_, r) -> r.copy(base = r.base?.let(::local), merged = r.merged.map(::local)) }
        }

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
                "SELECT version, fingerprint, content_hash, base_version, base_fingerprint FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}",
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
                        contentHash = rs.getString("content_hash"),
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
