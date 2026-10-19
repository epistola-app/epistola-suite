// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Who depends on a catalog: the other side of its dependencies. */
class ListCatalogDependentsTest : IntegrationTestBase() {

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")
    private val forms = CatalogKey.of("forms")

    @Test
    fun `a catalog lists the catalogs that declare a dependency on it, with the release they pin`() {
        val tenant = withMediator {
            val tenant = createTenant("dependents").id
            CreateCatalog(tenant, shared, "Shared").execute()
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, forms, "Forms").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant))), "Brand").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogVersion(tenant, shared, "1.0.0").execute()
            val invoice = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant)))
            CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute()
            declareDependency(tenant, letters, on = shared)
            tenant
        }

        val dependents = withMediator { ListCatalogDependents(tenant, shared).query() }

        assertThat(dependents.map { Triple(it.catalogKey, it.name, it.lockedTo) }).containsExactly(Triple("letters", "Letters", "1.0.0"))
        assertThat(withMediator { ListCatalogDependents(tenant, letters).query() }).`as`("nothing depends on Letters").isEmpty()
    }
}
