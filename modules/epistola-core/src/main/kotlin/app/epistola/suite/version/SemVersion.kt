// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.version

/**
 * Minimal SemVer parser/comparator for suite release labels.
 *
 * Build metadata is ignored for precedence and the local `-SNAPSHOT` development suffix is stripped
 * before parsing so `1.0.0-RC3-SNAPSHOT` compares as `1.0.0-RC3`.
 */
data class SemVersion(
    val core: List<Int>,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVersion> {
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemVersion): Int {
        for (i in 0..2) {
            val cmp = core[i].compareTo(other.core[i])
            if (cmp != 0) return cmp
        }
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val cmp = comparePreReleaseId(preRelease[i], other.preRelease[i])
            if (cmp != 0) return cmp
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        fun parse(version: String): SemVersion? {
            val normalized = normalize(version)
            val withoutBuild = normalized.substringBefore("+")
            val core = withoutBuild.substringBefore("-").split(".")
            if (core.size != 3) return null
            val numbers = core.map { it.toIntOrNull() ?: return null }
            val pre = withoutBuild.substringAfter("-", "").let { if (it.isEmpty()) emptyList() else it.split(".") }
            return SemVersion(numbers, pre)
        }

        fun normalize(version: String): String = version.removeSuffix("-SNAPSHOT")

        private fun comparePreReleaseId(a: String, b: String): Int {
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            return when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
