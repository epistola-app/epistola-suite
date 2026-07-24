// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.fonts.commands.EnsureSystemFontsHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Drift guard: every classpath resource the [EnsureSystemFontsHandler] seeder
 * declares (`SYSTEM_FONTS` × `BUNDLED_FACES`) must actually exist on the
 * classpath. Catches a hardcoded-list ↔ bundled-resource-tree divergence
 * (e.g. a renamed/removed TTF or a new family added to the list without its
 * files) without booting Spring or a DB.
 */
class SystemFontResourcesExistTest {

    @Test
    fun `every declared system font face resolves on the classpath`() {
        val locations = EnsureSystemFontsHandler.DECLARED_CLASSPATH_LOCATIONS
        assertThat(locations).isNotEmpty()

        val missing = locations.filter { loc ->
            this::class.java.classLoader.getResourceAsStream(loc) == null
        }

        assertThat(missing)
            .withFailMessage { "Declared system font resources missing from the classpath: $missing" }
            .isEmpty()
    }
}
