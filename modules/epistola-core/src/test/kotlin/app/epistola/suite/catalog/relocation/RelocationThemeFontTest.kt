// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.GetTenantResourceGraph
import app.epistola.suite.catalog.graph.ReferenceResolution
import app.epistola.suite.catalog.snapshot.BuildTenantSnapshot
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A theme names its fonts in its styles, which are live, unversioned configuration -- the kind of
 * reference the ADR says a move rewrites. These check that a theme still names a font that exists
 * after either end of that reference moves.
 */
class RelocationThemeFontTest : RelocationTestSupport() {

    @Test
    fun `after a font moves, a theme using it still exports and the tenant can still be snapshotted`() {
        val tenant = tenantWith("Theme after font move", listOf(letters, shared, archive))
        importFont(tenant, letters, "acme")
        createTheme(tenant, archive, fontCatalog = letters.value)

        move(tenant, address(tenant, CatalogResourceType.FONT, letters, "acme").movedTo(shared))

        withMediator { BuildTenantSnapshot(tenant).execute() }
        val theme = withMediator { mediator.exportReleased(testCatalog(app.epistola.suite.common.ids.TenantId(tenant), archive)) }.zipBytes.let(::unzipText).getValue("resources/theme/brand.json")
        assertThat(theme).contains("\"catalogKey\":\"shared\"")
    }

    /**
     * The theme names its font without a catalog, so the font resolves in whichever catalog the
     * theme lives in. Moving the theme without the font would leave it naming a font that is not
     * there.
     */
    @Test
    fun `after a theme moves, a font it named relatively still resolves`() {
        val tenant = tenantWith("Theme with relative font moves")
        importFont(tenant, letters, "acme")
        createTheme(tenant, letters, fontCatalog = null)

        move(tenant, address(tenant, CatalogResourceType.THEME, letters, "brand").movedTo(shared))

        val edge = withMediator { GetTenantResourceGraph(tenant, includeHistory = false).query() }.edges
            .single { it.source == address(tenant, CatalogResourceType.THEME, shared, "brand") && it.targetSelector.type == CatalogResourceType.FONT }
        assertThat(edge.resolution).isEqualTo(ReferenceResolution.RESOLVED)
        assertThat(edge.target).isEqualTo(address(tenant, CatalogResourceType.FONT, letters, "acme"))
        withMediator { BuildTenantSnapshot(tenant).execute() }
    }

    private fun createTheme(tenant: TenantKey, catalog: CatalogKey, fontCatalog: String?) = withMediator {
        CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, catalog)), "Brand", documentStyles = fontStyle("acme", fontCatalog)).execute()
    }
}
