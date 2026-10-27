// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ThemeStorageConversion
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.Theme
import app.epistola.suite.themes.queries.ListThemes
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * Every theme of the demo catalog reads the same from its next version and from the release it was
 * installed as (ADR 0033, step 1): one read interface and one mapper, whatever the version; and
 * `V20261101000600` converts each, from its 1.x columns, exactly as the suite stores it.
 */
class DemoThemeReadParityTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"

    private fun Theme.content() = listOf(id, catalogKey, name, description, documentStyles, pageSettings, blockStylePresets, spacingUnit)

    @Test
    fun `every demo theme reads the same from the next version and from its release`() {
        val tenant = createTenant("Demo theme parity").id
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant, sourceUrl = demoUrl).execute() }
        val catalog = testCatalog(TenantId(tenant), CatalogKey.of("epistola-demo"))
        val release = withMediator { ListCatalogReleases(catalog).query() }.first { it.status == CatalogReleaseStatus.RELEASED }

        val drafted = withMediator { ListThemes(catalog.tenantId, catalog = catalog).query() }.sortedBy { it.id.value }
        val released = withMediator { ListThemes(catalog.tenantId, catalog = catalog, catalogVersion = CatalogReleaseRef(catalog, DraftRef.Released(requireNotNull(release.version)))).query() }.sortedBy { it.id.value }

        assertThat(drafted).isNotEmpty()
        assertThat(released.map { it.content() }).isEqualTo(drafted.map { it.content() })
        // And the migration that moved themes to JSON storage converts each as the suite stores it.
        ThemeStorageConversion.verify(jdbi, objectMapper, catalog, drafted)
    }
}
