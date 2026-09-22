// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.snapshot.BuildTenantSnapshot
import app.epistola.suite.catalog.snapshot.RestoreTenantSnapshot
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The cycle guard exists because snapshot restore orders catalogs by their export dependencies and
 * throws on a cycle. It must refuse a move that would create such a cycle -- and nothing else.
 */
class RelocationCycleGuardTest : RelocationTestSupport() {

    /**
     * A font's face binaries travel inside the font when its catalog is exported, so where a face's
     * binary happens to be stored is not a dependency between catalogs. A theme using a font from
     * its own catalog is the everyday case; moving that font must not be refused as a cycle.
     */
    @Test
    fun `a font used by a theme in its own catalog can move, and the snapshot still restores`() {
        val tenant = tenantWith("Font move is not a cycle")
        importFont(tenant, letters, "acme")
        withMediator {
            CreateTheme(
                ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)),
                "Brand",
                documentStyles = mapOf("fontFamily" to mapOf("slug" to "acme", "catalogKey" to letters.value)),
            ).execute()
        }
        val font = address(CatalogResourceType.FONT, letters, "acme")

        val plan = preview(tenant, font.movedTo(shared))
        assertThat(plan.blockers).isEmpty()
        move(tenant, font.movedTo(shared))

        val snapshot = withMediator { BuildTenantSnapshot(tenant).execute() }
        withMediator { RestoreTenantSnapshot(tenant, snapshot.bytes).execute() }
        assertThat(identityAt(tenant, address(CatalogResourceType.FONT, shared, "acme"))).isNotNull()
        assertThat(identityAt(tenant, font)).isNull()
    }
}
