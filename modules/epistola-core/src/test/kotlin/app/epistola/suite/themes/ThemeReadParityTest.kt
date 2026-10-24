// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.ListThemes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A theme reads the same from a draft and from the release that holds the same content (ADR 0033,
 * step 1a): one read interface, `current_themes`, and one mapper from the theme's JSON, whatever
 * version it is of. Every theme of the bundled system catalog is read both ways: from its next
 * version, which holds what it was installed with, and from that release.
 */
class ThemeReadParityTest : IntegrationTestBase() {

    /** What a theme says, without the bookkeeping a draft row and a release row keep apart. */
    private fun Theme.content() = listOf(id, catalogKey, name, description, documentStyles, pageSettings, blockStylePresets, spacingUnit)

    @Test
    fun `every bundled theme reads the same from the next version and from the release it holds`() {
        val tenant = createTenant("Theme read parity").id
        val catalog: CatalogId = systemCatalog(tenant)
        val release = withMediator { ListCatalogReleases(catalog).query() }.first { it.status == CatalogReleaseStatus.RELEASED }
        val released = CatalogReleaseRef(catalog, DraftRef.Released(requireNotNull(release.version)))

        val drafted = withMediator { ListThemes(catalog.tenantId, catalog = catalog).query() }.sortedBy { it.id.value }
        val fromRelease = withMediator { ListThemes(catalog.tenantId, catalog = catalog, catalogVersion = released).query() }.sortedBy { it.id.value }

        assertThat(drafted).isNotEmpty()
        assertThat(fromRelease.map { it.content() }).isEqualTo(drafted.map { it.content() })
        for (theme in drafted) {
            val one = withMediator { GetTheme(ThemeId(theme.id, catalog), catalogVersion = released).query() }
            assertThat(one?.content()).`as`(theme.id.value).isEqualTo(theme.content())
        }
    }

    @Test
    fun `a release a catalog does not have is not found`() {
        val tenant = createTenant("Theme read missing").id
        val catalog = systemCatalog(tenant)

        val missing = runCatching { withMediator { ListThemes(catalog.tenantId, catalog = catalog, catalogVersion = CatalogReleaseRef(catalog, DraftRef.Released("99.0.0"))).query() } }
        assertThat(missing.exceptionOrNull()).isInstanceOf(app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException::class.java)
    }
}
