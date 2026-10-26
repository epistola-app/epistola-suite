// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.RENDERING_DEFAULTS_ENTRY
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A draft that renders with newer rendering defaults than its base release was cut with has a change
 * to release, a minor one, even when its content is the release's (#1127): moving a catalog to new
 * defaults is a deliberate release. A draft with the same defaults and no other change still has none.
 */
class RenderingDefaultsChangeTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    private val catalog = CatalogKey.of("rendered")

    @Test
    fun `an unchanged draft on a release cut with older defaults releases as a minor, and then has nothing to release`() {
        val tenant = createTenant("Rendering defaults").id
        val id = testCatalog(tenant, catalog)
        withMediator {
            CreateCatalog(tenantKey = tenant, id = catalog, name = "Rendered").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), id), name = "Brand").execute()
            MarkCatalogReady(id).execute()
            ReleaseCatalogDraft(catalog = id, version = "1.0.0").execute()
        }
        // A release cut before the current defaults existed: no command cuts one with older defaults.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE catalog_releases SET rendering_defaults_version = 6 WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
                .bind("t", tenant).bindCatalog("c", id).execute()
        }
        val current = RenderingDefaults.CURRENT.version

        withMediator {
            val change = GetCatalogResourceChanges(id).query().settings.single { it.entry == RENDERING_DEFAULTS_ENTRY }
            assertThat(change.name).isEqualTo("Rendering defaults V6 → V$current")
            assertThat(change.description).startsWith("documents may look different")

            assertThatThrownBy { ReleaseCatalogDraft(catalog = id, version = "1.0.1", requireChange = true).execute() }
                .`as`("new defaults are more than a fix").isInstanceOf(CatalogReleaseVersionException::class.java)
            ReleaseCatalogDraft(catalog = id, version = "1.1.0", requireChange = true).execute()

            assertThat(GetCatalogResourceChanges(id).query().settings.none { it.entry == RENDERING_DEFAULTS_ENTRY }).`as`("cut with the current defaults").isTrue()
            assertThatThrownBy { ReleaseCatalogDraft(catalog = id, version = "1.2.0", requireChange = true).execute() }
                .`as`("the same defaults and no other change").isInstanceOf(CatalogDraftUnchangedException::class.java)
        }
    }
}
