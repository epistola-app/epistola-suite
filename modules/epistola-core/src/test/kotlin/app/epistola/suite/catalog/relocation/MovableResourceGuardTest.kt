// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ReferenceSemantics
import app.epistola.suite.catalog.graph.ReferenceSiteKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MovableResourceGuardTest {
    /**
     * Runtime-resolved types whose lookup follows aliases, and the query that does it.
     *
     * Adding an entry here is a claim that a published document still renders after the type
     * moves. It is deliberately a hand-maintained list rather than something derived: the
     * behaviour it asserts lives in a SQL fallback that nothing else would notice the loss of, and
     * the integration tests named alongside each entry are what actually prove it.
     */
    private val aliasAwareRuntimeLookups = mapOf(
        // `a qualified image reference survives its asset moving`
        CatalogResourceType.ASSET to "GetAssetContent",
        // `a published document keeps its typeface after the font family moves`
        CatalogResourceType.FONT to "ResolveFontFace",
    )

    /**
     * Stencil content is inlined when it is inserted, so a stencil reference records where content
     * came from rather than something resolved while rendering — which is why moving a stencil
     * cannot break generation. Themes, fonts and assets are not like that: a published template
     * resolves them at render time, by address.
     *
     * Registering one of those as movable before its runtime resolution follows aliases would mean
     * every published template referencing it fails to render after a move, with nothing in the
     * move preview hinting at it. This guard makes that a build failure rather than a production
     * discovery. Adding a type to [aliasAwareRuntimeLookups] is the deliberate statement that the
     * corresponding lookup now resolves historical addresses.
     */
    @Test
    fun `no movable type is resolved at render time by an address that ignores aliases`() {
        val runtimeResolvedTypes = ReferenceSiteKind.entries
            .filter { it.semantics == ReferenceSemantics.RUNTIME }
            .map { it.type }
            .toSet()
        val offenders = MovableResource.entries
            .map { it.type }
            .filter { it in runtimeResolvedTypes && it !in aliasAwareRuntimeLookups }
        assertThat(offenders)
            .withFailMessage(
                "%s is registered as movable but is resolved at render time by address. " +
                    "Make that lookup alias-aware first, then record it in aliasAwareRuntimeLookups.",
                offenders,
            )
            .isEmpty()
    }

    /** A claim about a type that cannot move is dead weight, and hides that the list went stale. */
    @Test
    fun `every alias-aware lookup is claimed for a type that can actually move`() {
        val movable = MovableResource.entries.map { it.type }.toSet()
        assertThat(aliasAwareRuntimeLookups.keys).allSatisfy { assertThat(it).isIn(movable) }
    }
}
