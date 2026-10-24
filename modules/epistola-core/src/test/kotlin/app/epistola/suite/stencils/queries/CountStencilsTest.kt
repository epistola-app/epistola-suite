// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The total of a stencil list page counts what [ListStencils] would list without its limit. */
class CountStencilsTest : IntegrationTestBase() {

    @Test
    fun `counts the stencils the same filters list, ignoring the page`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("count-stencils").id)
        val catalogA = CatalogKey.of("cat-a")
        val catalogB = CatalogKey.of("cat-b")
        CreateCatalog(tenantKey = tenantId.key, id = catalogA, name = "Catalog A").execute()
        CreateCatalog(tenantKey = tenantId.key, id = catalogB, name = "Catalog B").execute()
        fun stencil(key: String, catalog: CatalogKey, name: String, tags: List<String>) = CreateStencil(StencilId(StencilKey.of(key), testCatalog(tenantId, catalog)), name, tags = tags).execute()
        stencil("header", catalogA, "Letter header", listOf("letter"))
        stencil("footer", catalogA, "Letter footer", listOf("letter", "legal"))
        stencil("signature", catalogA, "Signature", listOf("legal"))
        stencil("other", catalogB, "Letter elsewhere", listOf("letter"))

        val filters = listOf(
            Triple(null, null, catalogA),
            Triple("letter", null, catalogA),
            Triple(null, "legal", catalogA),
            Triple("letter", "letter", null),
            Triple(null, null, null),
        )
        for ((search, tag, catalog) in filters) {
            val listed = ListStencils(tenantId, searchTerm = search, tag = tag, catalog = catalog?.let { testCatalog(tenantId, it) }, limit = 1).query()
            val all = ListStencils(tenantId, searchTerm = search, tag = tag, catalog = catalog?.let { testCatalog(tenantId, it) }, limit = 100).query()
            val count = CountStencils(tenantId, searchTerm = search, tag = tag, catalog = catalog?.let { testCatalog(tenantId, it) }).query()
            assertThat(count).describedAs("search=$search tag=$tag catalog=$catalog").isEqualTo(all.size.toLong())
            assertThat(listed.size.toLong()).isLessThanOrEqualTo(count)
        }
        assertThat(CountStencils(tenantId, catalog = testCatalog(tenantId, catalogA)).query()).isEqualTo(3)
        assertThat(CountStencils(tenantId, searchTerm = "letter", tag = "legal", catalog = testCatalog(tenantId, catalogA)).query()).isEqualTo(1)
    }
}
