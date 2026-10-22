// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

/**
 * Why a draft lacks the releases it is behind, as a picture (ADR 0032): the line of releases it
 * continues, and where each release it lacks was made off that line. The web UI draws it above the
 * list of those releases, so a reader sees at a glance why there are several and where each came
 * from ([ReleaseLineage.behindLine]).
 *
 * @property line the releases the draft continues, oldest first: its base, that release's base and so
 *   on back (first parents only), from the oldest one a release it lacks was made on up to its base.
 *   Just its base when no release it lacks was made on the line; empty for a draft started from
 *   nothing.
 * @property joined releases merged in on the way, into a release on [line] or into the draft itself:
 *   they are in it, so they are drawn joined to the line and never as lacking. Lowest first.
 * @property behind the releases the draft lacks, in the order it is behind them (lowest first).
 */
data class BehindLine(
    val line: List<String>,
    val joined: List<JoinedRelease>,
    val behind: List<BehindBranch>,
)

/**
 * A release merged in on the line a draft continues: [into] is the release on the line that took it
 * in, or null for the draft itself.
 */
data class JoinedRelease(val version: String, val into: String?)

/**
 * A release a draft lacks, drawn off [madeOn]: the drawn release it was made on (one on the line, one
 * merged in, or another it lacks), or null when that release is not here, for one received from
 * elsewhere, or one that records no base.
 */
data class BehindBranch(val version: String, val madeOn: String?)
