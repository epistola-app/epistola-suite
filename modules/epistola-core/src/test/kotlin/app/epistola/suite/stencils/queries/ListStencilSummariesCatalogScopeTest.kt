package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Regression test for #466: list endpoints must filter by catalog, not just tenant. */
class ListStencilSummariesCatalogScopeTest : IntegrationTestBase() {

    private fun test(block: () -> Unit) = withMediator(block)

    @Test
    fun `list filters by catalogKey when set`() = test {
        val tenant = createTenant("Stencil Catalog Scope")
        val tenantId = TenantId(tenant.id)
        val catalogA = CatalogKey.of("cat-a")
        val catalogB = CatalogKey.of("cat-b")
        CreateCatalog(tenantKey = tenant.id, id = catalogA, name = "Catalog A").execute()
        CreateCatalog(tenantKey = tenant.id, id = catalogB, name = "Catalog B").execute()

        val stencilA = StencilId(StencilKey.of("in-cat-a"), CatalogId(catalogA, tenantId))
        val stencilB = StencilId(StencilKey.of("in-cat-b"), CatalogId(catalogB, tenantId))
        CreateStencil(id = stencilA, name = "Catalog A Stencil").execute()
        CreateStencil(id = stencilB, name = "Catalog B Stencil").execute()

        val onlyA = ListStencilSummaries(tenantId = tenantId, catalogKey = catalogA).query()
        assertThat(onlyA.map { it.id.value }).contains("in-cat-a").doesNotContain("in-cat-b")

        val onlyB = ListStencilSummaries(tenantId = tenantId, catalogKey = catalogB).query()
        assertThat(onlyB.map { it.id.value }).contains("in-cat-b").doesNotContain("in-cat-a")

        val allCatalogs = ListStencilSummaries(tenantId = tenantId).query()
        assertThat(allCatalogs.map { it.id.value }).contains("in-cat-a", "in-cat-b")
    }
}
