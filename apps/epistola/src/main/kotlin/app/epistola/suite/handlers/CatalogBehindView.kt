// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.releases.BehindBranch
import app.epistola.suite.catalog.releases.BehindLine
import app.epistola.suite.catalog.releases.BehindRelease
import app.epistola.suite.catalog.releases.ReleasedChange
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

/**
 * The published releases a draft lacks (ADR 0032), as the catalog's page and the draft's page both
 * show them (`catalogs/behind.html`), built in one place so the two cannot drift apart. One release
 * is said in a sentence. Several are a group: a title with the count, a drawing of the line the draft
 * continues with each of them off the release it was made on ([BehindDiagram]), and a row each,
 * oldest first, saying the same as text.
 *
 * Whether to offer merging them is not decided here: the templates gate on
 * [CatalogReleaseReadiness.offerMerge] and [CatalogReleaseReadiness.mergeWithheld].
 *
 * @property inWhat how its sentences name the draft: "the next release", or "this draft".
 */
data class CatalogBehindView(
    val inWhat: String,
    val rows: List<Row>,
    val diagram: BehindDiagram?,
) {
    /** It lacks nothing: nothing to show. */
    val isEmpty: Boolean get() = rows.isEmpty()

    /** The one release it lacks, said in a sentence; null when it lacks none, or several. */
    val single: Row? get() = rows.singleOrNull()

    /** It lacks more than one, which is shown as a group with a drawing. */
    val several: Boolean get() = rows.size > 1

    /** The group's title: "2 releases are not in the next release yet". */
    val title: String get() = "${rows.size} releases are not in $inWhat yet"

    /** Why there are several, in one line. */
    val why: String get() = "They were published from separate work on earlier releases and never taken into $inWhat."

    /**
     * One release the draft lacks.
     *
     * @property madeOn where it was made, in words: "made on v1.0.0", "received from elsewhere, made on
     *   v0.9.0" for one made on a release this catalog does not have, "started from nothing" for one
     *   that names no base.
     * @property changed what it changed, in words ("changed Invoice and Brand, and added Footer"); null
     *   when it changed no resource.
     * @property summary the sentence after its number when it is the only one: "was made on v1.0.0 and
     *   is not in the next release: it changed Invoice."
     */
    data class Row(
        val version: String,
        val href: String,
        val madeOn: String,
        val changed: String?,
        val publishedAt: OffsetDateTime?,
        val summary: String,
    )

    companion object {
        /** Nothing lacking, for a page with no draft. */
        val NONE = CatalogBehindView("the next release", emptyList(), null)

        /** How many names a row lists per kind of change before "and n more". */
        private const val NAMES_SHOWN = 5

        /**
         * The view of [behind] (lowest first) and [line] for the draft called [draftLabel], the next
         * release when [isNext]; [released] is every release the catalog has, which says whether a
         * release was made on one of them.
         */
        fun of(
            catalog: app.epistola.suite.common.ids.CatalogId,
            draftLabel: String,
            isNext: Boolean,
            behind: List<BehindRelease>,
            line: BehindLine?,
            released: Set<String>,
        ): CatalogBehindView {
            val inWhat = if (isNext) "the next release" else "this draft"
            val rows = behind.map { release ->
                val base = release.base
                val (madeOn, clause) = when {
                    base == null -> "started from nothing" to "started from nothing"
                    base in released -> "made on v$base" to "was made on v$base"
                    else -> "received from elsewhere, made on v$base" to "was received from elsewhere, made on v$base,"
                }
                val changed = changedWords(release.changes)
                Row(
                    version = release.version,
                    href = CatalogPages.release(catalog, release.version),
                    madeOn = madeOn,
                    changed = changed,
                    publishedAt = release.releasedAt,
                    summary = "$clause and is not in $inWhat" + changed?.let { ": it $it" }.orEmpty() + ".",
                )
            }
            val madeOnOf = rows.associate { it.version to it.madeOn }
            val diagram = if (rows.size > 1 && line != null) BehindDiagram.of(line, draftLabel) { madeOnOf[it].orEmpty() } else null
            return CatalogBehindView(inWhat, rows, diagram)
        }

        /** What [changes] did, by name, in plain words: "changed Invoice and Brand, and added Footer". */
        fun changedWords(changes: List<ReleasedChange>): String? {
            val parts = listOf("modified" to "changed", "added" to "added", "removed" to "removed").mapNotNull { (change, verb) ->
                val names = changes.filter { it.change == change }.map { it.name ?: it.key }
                if (names.isEmpty()) null else "$verb " + andList(if (names.size > NAMES_SHOWN) names.take(NAMES_SHOWN) + "${names.size - NAMES_SHOWN} more" else names)
            }
            return when (parts.size) {
                0 -> null
                1 -> parts.single()
                else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
            }
        }

        /** "a", "a and b", "a, b and c". */
        fun andList(items: List<String>): String = if (items.size < 2) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}

/**
 * The line a draft continues and the releases it lacks off it ([BehindLine]), laid out for drawing as
 * SVG on the server (the CSP allows no script for it, and none is needed): the line left to right,
 * oldest first, ending in the draft; each release it lacks below, off the release it was made on and
 * marked "not in it"; each release merged in above, joined to the release that took it in. A stretch
 * of the line with nothing off it folds into one "n more", so the drawing stays small.
 *
 * @property description the drawing in words, its accessible name; the list beside it says the same.
 */
data class BehindDiagram(val width: Int, val height: Int, val nodes: List<Node>, val edges: List<Edge>, val description: String) {

    /** What a node is: a release on the line, a folded stretch of them, the draft, one merged in, one it lacks. */
    enum class Kind { LINE, FOLDED, DRAFT, JOINED, BEHIND }

    /**
     * A box with its [label]; [version] null for the draft and a folded stretch. [title] is its
     * tooltip. [unattached] is a release it lacks that was made on none drawn here, drawn dashed.
     */
    data class Node(
        val version: String?,
        val label: String,
        val kind: Kind,
        val x: Int,
        val y: Int,
        val width: Int,
        val title: String,
        val unattached: Boolean = false,
    ) {
        val height: Int get() = NODE_HEIGHT
        val centerX: Int get() = x + width / 2
        val textY: Int get() = y + 17

        /** "not in it" under a release the draft lacks, "merged in" under one it took in. */
        val caption: String? get() = when (kind) {
            Kind.BEHIND -> "not in it"
            Kind.JOINED -> "merged in"
            else -> null
        }
        val captionY: Int get() = y + NODE_HEIGHT + 13
    }

    /** A connection; [toBehind] for one leading to a release the draft lacks, drawn dashed in the warning colour. */
    data class Edge(val path: String, val toBehind: Boolean)

    /** A node being placed: [left] and [row] before the drawing is moved to its margins. */
    private class Placed(
        val version: String?,
        val label: String,
        val kind: Kind,
        val left: Int,
        val width: Int,
        val row: Int,
        val title: String,
        val parent: Placed? = null,
        val into: Placed? = null,
        val unattached: Boolean = false,
    ) {
        val centerX: Int get() = left + width / 2
        val right: Int get() = left + width
    }

    companion object {
        const val NODE_HEIGHT = 26

        /** From the top of one row to the top of the next, leaving room for a caption between. */
        private const val ROW = 50

        /** Between two releases on the line. */
        private const val GAP = 28

        /** How far right of the release it was made on (or left of the one that took it in) a branch sits. */
        private const val OFFSET = 14

        /** The least room between two boxes in one row. */
        private const val CLEARANCE = 10

        private const val MARGIN = 12
        private const val CORNER = 6

        /** This many releases in a row with nothing off them fold into one "n more". */
        private const val FOLD_FROM = 3

        /** A draft's name longer than this is cut, in full in its tooltip. */
        private const val LABEL_MAX = 24

        /** The width a label takes at 12px, estimated generously, padding included. */
        private fun widthOf(label: String): Int = maxOf(48, 16 + Math.ceil(label.length * 6.8).toInt())

        /** Lays out [line] ending in the draft called [draftLabel]; [madeOn] says where a release it lacks was made. */
        fun of(line: BehindLine, draftLabel: String, madeOn: (String) -> String): BehindDiagram {
            val anchors = line.behind.mapNotNull { it.madeOn }.toSet() + line.joined.mapNotNull { it.into }
            val occupied = mutableMapOf<Int, MutableList<IntRange>>()
            fun free(row: Int, left: Int, width: Int) = occupied[row].orEmpty().none { left < it.last + CLEARANCE && it.first < left + width + CLEARANCE }
            fun claim(row: Int, left: Int, width: Int) {
                occupied.getOrPut(row) { mutableListOf() } += left..(left + width)
            }

            // The first row from [from], going [step], with room; each row holds at least one box, so it ends.
            fun rowFor(left: Int, width: Int, from: Int, step: Int): Int {
                var row = from
                while (!free(row, left, width)) row += step
                claim(row, left, width)
                return row
            }

            // Row 0: the line, a folded stretch where nothing hangs off it, then the draft.
            val onLine = mutableListOf<Placed>()
            var x = 0
            fun putOnLine(version: String?, label: String, kind: Kind, title: String): Placed {
                val width = widthOf(label)
                return Placed(version, label, kind, x, width, 0, title).also {
                    claim(0, x, width)
                    onLine += it
                    x += width + GAP
                }
            }
            stretches(line.line, anchors).forEach { stretch ->
                if (stretch.size == 1) {
                    putOnLine(stretch.single(), "v${stretch.single()}", Kind.LINE, "v${stretch.single()}")
                } else {
                    putOnLine(null, "${stretch.size} more", Kind.FOLDED, stretch.joinToString(", ") { "v$it" })
                }
            }
            val draft = putOnLine(null, if (draftLabel.length > LABEL_MAX) draftLabel.take(LABEL_MAX - 1) + "…" else draftLabel, Kind.DRAFT, draftLabel)
            val placed = onLine.filter { it.version != null }.associateBy { it.version!! }.toMutableMap()

            // Above: each release merged in, just left of the release (or the draft) that took it in.
            val joined = line.joined.mapNotNull { release ->
                val into = if (release.into == null) draft else placed[release.into] ?: return@mapNotNull null
                val label = "v${release.version}"
                val width = widthOf(label)
                val left = into.centerX - OFFSET - width
                Placed(
                    version = release.version,
                    label = label,
                    kind = Kind.JOINED,
                    left = left,
                    width = width,
                    row = rowFor(left, width, -1, -1),
                    title = "v${release.version}: merged in",
                    into = into,
                )
            }
            joined.forEach { placed[it.version!!] = it }

            // Below (or further above, off one merged in): each release it lacks, just right of the one it was made on.
            val lacking = line.behind.associateBy { it.version }
            val lines = onLine.filter { it.kind == Kind.LINE }
            fun place(branch: BehindBranch, seen: Set<String>): Placed {
                placed[branch.version]?.let { return it }
                val parent = branch.madeOn?.let { madeOnVersion ->
                    placed[madeOnVersion] ?: lacking[madeOnVersion]?.takeIf { madeOnVersion !in seen }?.let { place(it, seen + branch.version) }
                }
                val label = "v${branch.version}"
                val width = widthOf(label)
                // Made on nothing drawn here: after the last release on the line numbered below it.
                val left = parent?.let { it.centerX + OFFSET } ?: lines.lastOrNull { below(it.version!!, branch.version) }?.let { it.centerX + OFFSET } ?: 0
                val row = when {
                    parent == null -> rowFor(left, width, 1, 1)
                    parent.row < 0 -> rowFor(left, width, parent.row - 1, -1)
                    else -> rowFor(left, width, parent.row + 1, 1)
                }
                return Placed(
                    version = branch.version,
                    label = label,
                    kind = Kind.BEHIND,
                    left = left,
                    width = width,
                    row = row,
                    title = "v${branch.version}, ${madeOn(branch.version)}: not in it",
                    parent = parent,
                    unattached = parent == null,
                ).also { placed[branch.version] = it }
            }
            val behind = line.behind.map { place(it, emptySet()) }

            // Moved to the margins.
            val all = onLine + joined + behind
            val dx = MARGIN - all.minOf { it.left }
            val top = all.minOf { it.row }
            val bottom = all.maxOf { it.row }
            fun y(p: Placed) = MARGIN + (p.row - top) * ROW
            fun cy(p: Placed) = y(p) + NODE_HEIGHT / 2
            fun cx(p: Placed) = p.centerX + dx
            val nodes = all.map { Node(it.version, it.label, it.kind, it.left + dx, y(it), it.width, it.title, it.unattached) }

            val edges = mutableListOf<Edge>()
            onLine.zipWithNext { a, b -> edges += Edge("M ${a.right + dx} ${cy(a)} H ${b.left + dx}", toBehind = false) }
            joined.forEach { j ->
                val into = j.into!!
                edges += Edge("M ${j.right + dx} ${cy(j)} H ${cx(into) - CORNER} Q ${cx(into)} ${cy(j)}, ${cx(into)} ${cy(j) + CORNER} V ${y(into)}", toBehind = false)
            }
            behind.forEach { b ->
                val p = b.parent ?: return@forEach
                val (from, turn) = if (b.row > p.row) (y(p) + NODE_HEIGHT) to (cy(b) - CORNER) else y(p) to (cy(b) + CORNER)
                edges += Edge("M ${cx(p)} $from V $turn Q ${cx(p)} ${cy(b)}, ${cx(p) + CORNER} ${cy(b)} H ${b.left + dx}", toBehind = true)
            }

            val captions = if (bottom > 0) 16 else 0
            return BehindDiagram(
                width = all.maxOf { it.right } + dx + MARGIN,
                height = MARGIN + (bottom - top) * ROW + NODE_HEIGHT + captions + MARGIN,
                nodes = nodes,
                edges = edges,
                description = describe(line, draftLabel, madeOn),
            )
        }

        /** [line] in runs: a release alone, or [FOLD_FROM] or more in a row with nothing off them, folded. */
        private fun stretches(line: List<String>, anchors: Set<String>): List<List<String>> {
            val kept = line.mapIndexed { i, release -> i == 0 || i == line.lastIndex || release in anchors }
            val result = mutableListOf<List<String>>()
            var i = 0
            while (i < line.size) {
                var end = i
                while (end < line.size && !kept[end]) end++
                when {
                    end == i -> result += listOf(line[i]).also { end = i + 1 }
                    end - i >= FOLD_FROM -> result += line.subList(i, end)
                    else -> line.subList(i, end).forEach { result += listOf(it) }
                }
                i = end
            }
            return result
        }

        private fun below(release: String, than: String): Boolean {
            val a = SemVer.parseOrNull(release) ?: return false
            val b = SemVer.parseOrNull(than) ?: return false
            return a < b
        }

        /** "Next release continues from v1.0.0 and v1.1.0. Not in it: v1.0.1, made on v1.0.0; v1.1.1, made on v1.1.0." */
        private fun describe(line: BehindLine, draftLabel: String, madeOn: (String) -> String): String = listOfNotNull(
            if (line.line.isEmpty()) "$draftLabel started from nothing." else "$draftLabel continues from ${CatalogBehindView.andList(line.line.map { "v$it" })}.",
            line.joined.takeIf { it.isNotEmpty() }?.let { joined -> "Merged into it: ${CatalogBehindView.andList(joined.map { "v${it.version}" })}." },
            "Not in it: " + line.behind.joinToString("; ") { "v${it.version}, ${madeOn(it.version)}" } + ".",
        ).joinToString(" ")
    }
}
