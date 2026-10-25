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

        fun Stencil.content() = listOf(id, name, description, tags, content, parameterSchema, readsTemplateData)

        val drafted = withMediator { ListStencils(TenantId(tenant), catalog = catalog).query() }.sortedBy { it.id.value }
        val released = withMediator { ListStencils(TenantId(tenant), catalog = catalog, catalogVersion = CatalogReleaseRef(catalog, DraftRef.Released(requireNotNull(release.version)))).query() }.sortedBy { it.id.value }

        assertThat(drafted).isNotEmpty()
        assertThat(released.map { it.content() }).isEqualTo(drafted.map { it.content() })
        // Installed from one archive, the draft and the release name the same content.
        val divergent = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT d.resource_key FROM release_resources d
                JOIN release_resources r ON r.tenant_key = d.tenant_key AND r.catalog_id = d.catalog_id
                  AND r.resource_type = d.resource_type AND r.resource_key = d.resource_key AND r.version = :v
                WHERE d.tenant_key = :t AND d.version_id IS NOT NULL AND d.resource_type = 'stencil' AND d.content_hash <> r.content_hash
                """,
            ).bind("t", tenant).bind("v", release.version).mapTo(String::class.java).list()
        }
        assertThat(divergent).isEmpty()
        StencilStorageConversion.verify(jdbi, objectMapper, stencils, catalog)
    }
}
