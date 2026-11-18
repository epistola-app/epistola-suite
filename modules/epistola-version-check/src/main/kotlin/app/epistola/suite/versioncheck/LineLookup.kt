// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.version.SemVersion

/**
 * What the releases document says about the running version, read from `lines`.
 *
 * The website decides each line's status, so a policy change (a grace period, an extension) needs
 * no Suite release. This only looks the version up, by the rules every client follows
 * (https://epistola.app/releases.schema.json), and passes the published test cases
 * (https://epistola.app/releases.conformance.json, copied into this module's test resources):
 *
 * 1. Find the entry in `lines` for the version's major.minor.
 * 2. No entry: a version older than every listed line is [Status.ENDED]; any other (a development
 *    or pre-release build of a line not released yet) is [Status.UNKNOWN].
 * 3. Otherwise the entry's status; a value this client does not know is [Status.UNKNOWN].
 * 4. [Result.patch]: the entry's latest release when newer than the running version.
 * 5. [Result.newerRelease]: the newest stable release when it is on a newer line.
 * 6. [Result.nextPrerelease]: for a pre-release build, the newest pre-release when newer.
 *
 * [Status.UNKNOWN] means: say nothing about support.
 */
object LineLookup {
    enum class Status { SUPPORTED, ENDING, ENDED, UNKNOWN }

    data class Result(
        val status: Status = Status.UNKNOWN,
        /** The matched line, e.g. `2.0`, or null without an entry. */
        val line: String? = null,
        /** The matched line's last supported day, or null. */
        val supportEnds: String? = null,
        val patch: ReleaseRef? = null,
        val newerRelease: ReleaseRef? = null,
        val nextPrerelease: ReleaseRef? = null,
    )

    private val LINE = Regex("^(\\d+)\\.(\\d+)$")

    /** The answer for [version]; [Status.UNKNOWN] without lines or a SemVer version. */
    fun evaluate(product: ProductReleases?, version: String): Result {
        val lines = product?.lines ?: return Result()
        val running = SemVersion.parseStrict(version) ?: return Result()
        val own = lineOf(running)
        val listed = lines.mapNotNull { entry -> entry.line?.let(::parseLine)?.let { entry to it } }
        val match = listed.firstOrNull { (_, line) -> compareLines(line, own) == 0 }?.first

        val stable = product.stable
        val newerRelease = stable?.version?.let(SemVersion::parseStrict)
            ?.takeIf { compareLines(lineOf(it), own) > 0 }
            ?.let { stable }
        val nextPrerelease = if (running.isPreRelease) newer(product.next, running) else null

        if (match == null) {
            val olderThanAll = listed.isNotEmpty() && listed.all { (_, line) -> compareLines(own, line) < 0 }
            return Result(
                status = if (olderThanAll) Status.ENDED else Status.UNKNOWN,
                newerRelease = newerRelease,
                nextPrerelease = nextPrerelease,
            )
        }
        return Result(
            status = status(match.status),
            line = match.line,
            supportEnds = match.supportEnds,
            patch = newer(match.latest, running),
            newerRelease = newerRelease,
            nextPrerelease = nextPrerelease,
        )
    }

    private fun status(value: String?): Status = when (value) {
        "supported" -> Status.SUPPORTED
        "ending" -> Status.ENDING
        "ended" -> Status.ENDED
        else -> Status.UNKNOWN
    }

    private fun lineOf(version: SemVersion): Pair<Int, Int> = version.core[0] to version.core[1]

    private fun compareLines(a: Pair<Int, Int>, b: Pair<Int, Int>): Int = compareValuesBy(a, b, { it.first }, { it.second })

    private fun parseLine(line: String): Pair<Int, Int>? = LINE.matchEntire(line)?.destructured?.let { (major, minor) ->
        val majorNumber = major.toIntOrNull() ?: return null
        val minorNumber = minor.toIntOrNull() ?: return null
        majorNumber to minorNumber
    }

    private fun newer(ref: ReleaseRef?, than: SemVersion): ReleaseRef? = ref?.takeIf { release ->
        release.version?.let(SemVersion::parseStrict)?.let { it > than } ?: false
    }
}
