// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.ReferenceSemantics
import app.epistola.suite.catalog.graph.ReferenceSiteKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MovableResourceGuardTest {
    /**
     * Stencil content is inlined when it is inserted, so a stencil reference records where content
     * came from rather than something resolved while rendering — which is why moving a stencil
     * cannot break generation. Themes, fonts and assets are not like that: a published template
     * resolves them at render time, by address.
     *
     * Registering one of those as movable before its runtime resolution follows aliases would mean
     * every published template referencing it fails to render after a move, with nothing in the
     * move preview hinting at it. This guard makes that a build failure rather than a production
     * discovery. Removing an entry from the expected set is a deliberate statement that the
     * corresponding runtime lookup now resolves historical addresses.
     */
    @Test
    fun `no movable type is resolved at render time by address`() {
        val runtimeResolvedTypes = ReferenceSiteKind.entries
            .filter { it.semantics == ReferenceSemantics.RUNTIME }
            .map { it.type }
            .toSet()

        val offenders = MovableResource.entries
            .map { it.type }
            .filter { it in runtimeResolvedTypes }

        assertThat(offenders)
            .withFailMessage(
                "%s is registered as movable but is resolved at render time by address. " +
                    "Make that lookup alias-aware first, then remove this guard for that type.",
                offenders,
            )
            .isEmpty()
    }
}
