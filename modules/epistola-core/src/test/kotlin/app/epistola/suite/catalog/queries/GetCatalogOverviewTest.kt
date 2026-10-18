// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tenant's landing overview: every catalog with its drafts, latest release, what waits to be
 * marked ready and what each environment serves.
 */
class GetCatalogOverviewTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    @Test
    fun `each catalog says where it stands, its drafts and what they are behind, the latest release, what is not ready and what is served`() {
        val tenant = createTenant("overview").id
        val catalog = CatalogId(letters, TenantId(tenant))
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
            mediator.releaseNext(catalog) // 1.0.0; the next version continues on it
            CreateCatalogDraft(tenant, letters, "Redesign").execute()
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), catalog), "Seasonal").execute() // in the next version
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), "Production").execute()
            DeployRelease(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), letters, "1.0.0", includeDependencies = true).execute()
        }

        val overview = withMediator { GetCatalogOverview(tenant).query() }

        assertThat(overview.map { it.catalogKey.value }).`as`("authored first, then subscribed")
            .containsSubsequence("default", "letters", SYSTEM_CATALOG_KEY.value)
        val entry = overview.single { it.catalogKey == letters }
        assertThat(entry.type).isEqualTo(CatalogType.AUTHORED)
        assertThat(entry.drafts.map { it.label to it.isNext }).containsExactly("Next version" to true, "Redesign" to false)
        assertThat(entry.latestRelease?.version).isEqualTo("1.0.0")
        assertThat(entry.notReady).`as`("the new theme in the next version").isEqualTo(1)
        assertThat(entry.served).containsEntry("production", listOf("1.0.0"))

        val system = overview.single { it.catalogKey == SYSTEM_CATALOG_KEY }
        assertThat(system.drafts).isEmpty()
        assertThat(system.notReady).isNull()
    }
}
