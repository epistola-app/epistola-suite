// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.StencilStore
import app.epistola.suite.stencils.queries.ListStencils
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.StencilStorageConversion
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Every stencil of the demo catalog reads the same from its next version and from the release it was
 * installed as (ADR 0033, step 4), and `V20261025083103` converts each, from its 1.x columns,
 * exactly as the suite stores it.
 */
class DemoStencilReadParityTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var stencils: StencilStore

    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"

    @Test
    fun `every demo stencil reads the same from the next version and from its release`() {
        val tenant = createTenant("Demo stencil parity").id
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant, sourceUrl = demoUrl).execute() }
        val catalog = testCatalog(TenantId(tenant), CatalogKey.of("epistola-demo"))
        val release = withMediator { ListCatalogReleases(catalog).query() }.first { it.status == CatalogReleaseStatus.RELEASED }

        // A release names its own catalog's resources relatively; compared qualified, as the draft holds them.
        fun Stencil.content() = listOf(
            id,
            name,
            description,
            tags,
            objectMapper.valueToTree<JsonNode>(content).also { ResourceReferenceSites.qualifyRelative(it, catalog.key.value) },
            parameterSchema,
            readsTemplateData,
        )

        val drafted = withMediator { ListStencils(TenantId(tenant), catalog = catalog).query() }.sortedBy { it.id.value }
        val released = withMediator { ListStencils(TenantId(tenant), catalog = catalog, catalogVersion = CatalogReleaseRef(catalog, DraftRef.Released(requireNotNull(release.version)))).query() }.sortedBy { it.id.value }

        assertThat(drafted).isNotEmpty()
        assertThat(released.map { it.content() }).isEqualTo(drafted.map { it.content() })
        StencilStorageConversion.verify(jdbi, objectMapper, stencils, catalog)
    }
}
