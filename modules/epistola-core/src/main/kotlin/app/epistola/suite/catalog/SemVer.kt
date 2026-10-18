// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

/**
 * Minimal semantic version (`MAJOR.MINOR.PATCH`, optionally `-PRERELEASE`) for catalog release
 * versioning. Deliberately internal and dependency-free — only the operations catalog versioning
 * needs (parse, compare, bump, render). Build metadata is out of scope.
 *
 * A **pre-release** (`2.0.0-redesign-2027.1`, contract ADR 0002 amendment 2) is what long-term work
 * is released as: it sorts below the release of the same number, so the eventual `2.0.0` is above
 * it, and it is never "the latest release" (see [isPreRelease]). Precedence follows SemVer 2.0.0 §11.
 *
 * See [`docs/catalog-versioning.md`](../../../../../../../../docs/catalog-versioning.md).
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** The pre-release identifiers after `-`, dot-separated; null for a release. */
    val preRelease: String? = null,
) : Comparable<SemVer> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "SemVer components must be non-negative: $major.$minor.$patch"
        }
        require(preRelease == null || PRE_RELEASE.matches(preRelease)) { "Not a SemVer pre-release: '$preRelease'" }
    }

    /** A pre-release, which no "latest release" and no version range (unless it names one) ever means. */
    val isPreRelease: Boolean get() = preRelease != null

    /** The release this is a pre-release of, or itself. */
    val core: SemVer get() = if (preRelease == null) this else SemVer(major, minor, patch)

    fun bumpMajor(): SemVer = SemVer(major + 1, 0, 0)

    fun bumpMinor(): SemVer = SemVer(major, minor + 1, 0)

    fun bumpPatch(): SemVer = SemVer(major, minor, patch + 1)

    override fun compareTo(other: SemVer): Int {
        val core = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (core != 0) return core
        return when {
            preRelease == other.preRelease -> 0

            // A pre-release has lower precedence than the release of the same number.
            preRelease == null -> 1

            other.preRelease == null -> -1

            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    override fun toString(): String = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$""")
        private val PRE_RELEASE = Regex("""^[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*$""")

        /**
         * Parses a strict `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH-PRERELEASE` string.
         * @throws IllegalArgumentException if [value] is not a valid SemVer.
         */
        fun parse(value: String): SemVer = parseOrNull(value)
            ?: throw IllegalArgumentException("Not a SemVer (expected MAJOR.MINOR.PATCH): '$value'")

        /**
         * Lenient parse — returns `null` instead of throwing. Used where
         * legacy/bundled non-SemVer labels (e.g. `"5.5"`, `"1"`) must degrade
         * gracefully rather than crash.
         */
        fun parseOrNull(value: String): SemVer? = PATTERN.matchEntire(value.trim())?.destructured?.let { (a, b, c, pre) ->
            SemVer(a.toInt(), b.toInt(), c.toInt(), pre.ifEmpty { null })
        }

        /** [value] parsed when it is a release, not a pre-release; null otherwise. */
        fun releaseOrNull(value: String): SemVer? = parseOrNull(value)?.takeIf { !it.isPreRelease }

        /** SemVer 2.0.0 §11.4: identifier by identifier, numeric ones numerically and below alphanumeric ones. */
        private fun comparePreRelease(a: String, b: String): Int {
            val left = a.split('.')
            val right = b.split('.')
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]
                val y = right[i]
                val xn = x.toBigIntegerOrNull()
                val yn = y.toBigIntegerOrNull()
                val order = when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> -1
                    yn != null -> 1
                    else -> x.compareTo(y)
                }
                if (order != 0) return order
            }
            return left.size.compareTo(right.size)
        }
    }
}
