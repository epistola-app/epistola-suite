// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

/**
 * Minimal semantic version for catalog release numbers (`MAJOR.MINOR.PATCH`), deliberately internal
 * and dependency-free — only the operations catalog versioning needs (parse, compare, bump, render).
 *
 * A release number may carry one kind of pre-release label: a **patch release**
 * (`MAJOR.MINOR.PATCH-<namespace>.<N>`, contract ADR 0003, amendment of 2026-10-22; ADR 0034), a
 * release someone who does not hold the catalog's namespace issued, labelled with a namespace they
 * hold ([patchNamespace]) and a sequence number from 1 ([patchSequence]). It sorts as SemVer says:
 * `1.3.1-gemeente-x.1` above `1.3.0` and below `1.3.1`, labels compared as ASCII and then by number.
 * No other pre-release or build metadata. Bumping a patch release bumps its core: the next patch of
 * `1.3.1-gemeente-x.1` is `1.3.1`.
 *
 * See [`docs/catalog-versioning.md`](../../../../../../../../docs/catalog-versioning.md).
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** The namespace a patch release is labelled with, or null for a release without a label. */
    val patchNamespace: String? = null,
    /** The sequence number of a patch release, from 1, or null for a release without a label. */
    val patchSequence: Int? = null,
) : Comparable<SemVer> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "SemVer components must be non-negative: $major.$minor.$patch"
        }
        require((patchNamespace == null) == (patchSequence == null)) { "A patch release names both its namespace and its sequence number" }
        require(patchSequence == null || patchSequence >= 1) { "A patch release's sequence number counts from 1: $patchSequence" }
    }

    /** Whether this is a patch release ([patchNamespace]), numbered by someone who does not hold the namespace. */
    val isPatchRelease: Boolean get() = patchNamespace != null

    /** This number without its patch label. */
    val core: SemVer get() = if (isPatchRelease) SemVer(major, minor, patch) else this

    fun bumpMajor(): SemVer = SemVer(major + 1, 0, 0)

    fun bumpMinor(): SemVer = SemVer(major, minor + 1, 0)

    /** The next patch: a patch release's own core (it sorts below it), else the next patch number. */
    fun bumpPatch(): SemVer = if (isPatchRelease) core else SemVer(major, minor, patch + 1)

    override fun compareTo(other: SemVer): Int {
        val byCore = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (byCore != 0) return byCore
        // A release without a label sorts above every patch release of its core (SemVer: a pre-release is lower).
        if (patchNamespace == null) return if (other.patchNamespace == null) 0 else 1
        if (other.patchNamespace == null) return -1
        val byNamespace = patchNamespace.compareTo(other.patchNamespace)
        if (byNamespace != 0) return byNamespace
        return compareValues(patchSequence, other.patchSequence)
    }

    override fun toString(): String = if (isPatchRelease) "$major.$minor.$patch-$patchNamespace.$patchSequence" else "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(?=[a-z0-9-]{3,63}\.)([a-z][a-z0-9]*(?:-[a-z0-9]+)*)\.([1-9][0-9]*))?$""")

        /** The longest release number, a patch label included (contract `CatalogReleaseVersions.MAX_LENGTH`). */
        const val MAX_LENGTH: Int = 128

        /**
         * Parses `MAJOR.MINOR.PATCH`, or a patch release `MAJOR.MINOR.PATCH-<namespace>.<N>`.
         * @throws IllegalArgumentException if [value] is not a valid release number.
         */
        fun parse(value: String): SemVer = parseOrNull(value)
            ?: throw IllegalArgumentException("Not a SemVer (expected MAJOR.MINOR.PATCH): '$value'")

        /**
         * Lenient parse — returns `null` instead of throwing. Used where
         * legacy/bundled non-SemVer labels (e.g. `"5.5"`, `"1"`) must degrade
         * gracefully rather than crash.
         */
        fun parseOrNull(value: String): SemVer? {
            val trimmed = value.trim()
            if (trimmed.length > MAX_LENGTH) return null
            val match = PATTERN.matchEntire(trimmed) ?: return null
            val (a, b, c, namespace, sequence) = match.destructured
            return runCatching {
                SemVer(a.toInt(), b.toInt(), c.toInt(), namespace.ifEmpty { null }, sequence.ifEmpty { null }?.toInt())
            }.getOrNull()
        }
    }
}
