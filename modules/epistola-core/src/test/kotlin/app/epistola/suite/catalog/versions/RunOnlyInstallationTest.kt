// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.CatalogReadOnlyReason
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/** An installation with editing switched off (ADR 0032, section 3): nothing in a catalog is changed. */
@TestPropertySource(properties = ["epistola.catalog.editing.enabled=false"])
class RunOnlyInstallationTest : IntegrationTestBase() {

    @Test
    fun `nothing is changed or released, and the reason says the installation only runs releases`() {
        val tenant = createTenant("Run Only").id
        val catalog = CatalogKey.of("run-only")
        withMediator {
            assertThat(GetCatalogEditability(tenant, CatalogKey.DEFAULT).query().reason).isEqualTo(CatalogReadOnlyReason.EDITING_DISABLED)
            ListCatalogEditability(tenant).query().let { all ->
                assertThat(all.editableKeys).isEmpty()
                assertThat(all.of(CatalogKey.DEFAULT)?.reason).isEqualTo(CatalogReadOnlyReason.EDITING_DISABLED)
                assertThat(all.of(SYSTEM_CATALOG_KEY)?.reason).`as`("supplied wins: it is not changed even where editing is on").isEqualTo(CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA)
            }
            assertThatThrownBy { CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(CatalogKey.DEFAULT, TenantId(tenant))), name = "Brand").execute() }
                .isInstanceOf(CatalogReadOnlyException::class.java)
                .hasMessageContaining("only receives and runs releases")
            assertThatThrownBy { ReleaseCatalogVersion(tenantKey = tenant, catalogKey = CatalogKey.DEFAULT).execute() }
                .isInstanceOf(CatalogReadOnlyException::class.java)
            assertThatThrownBy { CreateCatalogDraft(tenant, CatalogKey.DEFAULT, "Work", null).execute() }
                .isInstanceOf(CatalogReadOnlyException::class.java)
            // Creating a catalog is managing the installation's catalogs, not changing one.
            CreateCatalog(tenantKey = tenant, id = catalog, name = "Run only").execute()
        }
    }
}
