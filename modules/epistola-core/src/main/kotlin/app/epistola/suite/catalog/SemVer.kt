// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

/**
 * Minimal semantic version (`MAJOR.MINOR.PATCH`) for catalog release
 * versioning. Deliberately internal and dependency-free — only the operations
 * catalog versioning needs (parse, compare, bump, render). Pre-release and
 * build metadata are intentionally out of scope.
 *
 * See [`docs/catalog-versioning.md`](../../../../../../../../docs/catalog-versioning.md).
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "SemVer components must be non-negative: $major.$minor.$patch"
        }
    }

    fun bumpMajor(): SemVer = SemVer(major + 1, 0, 0)

    fun bumpMinor(): SemVer = SemVer(major, minor + 1, 0)

    fun bumpPatch(): SemVer = SemVer(major, minor, patch + 1)

    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

        /**
         * Parses a strict `MAJOR.MINOR.PATCH` string.
         * @throws IllegalArgumentException if [value] is not a valid SemVer.
         */
        fun parse(value: String): SemVer = parseOrNull(value)
            ?: throw IllegalArgumentException("Not a SemVer (expected MAJOR.MINOR.PATCH): '$value'")

        /**
         * Lenient parse — returns `null` instead of throwing. Used where
         * legacy/bundled non-SemVer labels (e.g. `"5.5"`, `"1"`) must degrade
         * gracefully rather than crash.
         */
        fun parseOrNull(value: String): SemVer? = PATTERN.matchEntire(value.trim())?.destructured?.let { (a, b, c) ->
            SemVer(a.toInt(), b.toInt(), c.toInt())
        }
    }
}
