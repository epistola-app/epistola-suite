// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import org.semver4j.Semver

/**
 * A version selector, as generation, preview and validation take one to pick a release: a range in
 * the npm SemVer range grammar (`^1.4.0`, `~1.4`, `>=1.0.0 <2.0.0`, `1.2.3 - 1.4`, `1.x`, `*`, `a || b`).
 *
 * The grammar is checked here, against npm's own BNF, because the library that evaluates a range
 * also accepts other syntaxes (Ivy's `[1.0,2.0)`) that another implementation of the contract would
 * not. Evaluation follows npm: a pre-release only matches a range that names a pre-release of the
 * same version, a patch release's label included ([matches]), and build metadata (a hotfix's
 * `+hotfix.1`) does not affect precedence.
 */
class CatalogReleaseSelector private constructor(val value: String) {

    /**
     * Whether release [version] satisfies this selector; a version that is not SemVer never does.
     *
     * A patch release (`1.3.1-gemeente-x.1`, ADR 0034) is matched as npm matches a pre-release, as the
     * contract says ("Version ranges"): only by a range with a comparator naming a pre-release of the
     * same `MAJOR.MINOR.PATCH` (`>=1.3.1-gemeente-x.1`, or the exact number); `^1.3.0` never takes it.
     */
    fun matches(version: String): Boolean = Semver.parse(version)?.satisfies(value) ?: false

    /** The highest of [versions] this selector matches, by SemVer precedence, or null when none does. */
    fun highestMatch(versions: Collection<String>): String? = versions
        .filter(::matches)
        .mapNotNull { version -> Semver.parse(version)?.let { version to it } }
        .maxWithOrNull { a, b -> a.second.compareTo(b.second) }
        ?.first

    override fun equals(other: Any?): Boolean = other is CatalogReleaseSelector && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        /**
         * Longest selector a request may give (the contract's `versionRange` on generation, preview and
         * validation): 256, so that an exact release number of any valid length, 128, fits.
         */
        const val MAX_LENGTH = 256

        /** Parses [value], or null when it is not an npm range of at most [MAX_LENGTH] characters. */
        fun parseOrNull(value: String): CatalogReleaseSelector? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
            return if (RANGE_SET.matches(trimmed)) CatalogReleaseSelector(trimmed) else null
        }

        // npm's range BNF (node-semver README), with its optional `v`/`=` prefix on a partial.
        private const val NR = "(?:0|[1-9][0-9]*)"
        private const val XR = "(?:[xX*]|$NR)"
        private const val PART = "(?:$NR|[-0-9A-Za-z]+)"
        private const val PARTS = "$PART(?:\\.$PART)*"
        private const val QUALIFIER = "(?:-$PARTS)?(?:\\+$PARTS)?"
        private const val PARTIAL = "[vV=]?$XR(?:\\.$XR(?:\\.$XR$QUALIFIER)?)?"
        private const val PRIMITIVE = "(?:<=|>=|<|>|=)\\s*$PARTIAL"
        private const val SIMPLE = "(?:$PRIMITIVE|~>?\\s*$PARTIAL|\\^\\s*$PARTIAL|$PARTIAL)"
        private const val HYPHEN = "$PARTIAL\\s+-\\s+$PARTIAL"
        private const val RANGE = "(?:$HYPHEN|$SIMPLE(?:\\s+$SIMPLE)*|)"
        private val RANGE_SET = Regex("^\\s*$RANGE\\s*(?:\\|\\|\\s*$RANGE\\s*)*$")
    }
}
