// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * A release page shows what that release froze, not what the catalog looks like now.
 *
 * The catalog's own details — its name, description and keywords — are as version-dependent as its
 * resources, and the browse page has only ever shown the working copy's. Reading them from the live
 * row here would reproduce exactly the confusion this page exists to remove.
 */
class CatalogReleaseDetailTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the page shows the catalog details and resources the release froze, not today's`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Release Detail")
                withMediator {
                    val catalogKey = CatalogKey.of("detail-cat")
                    val catalog = CatalogId(catalogKey, TenantId(tenant.id))
                    CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Detail cat").execute()
                    UpdateCatalogMetadata(
                        tenantKey = tenant.id,
                        catalogKey = catalogKey,
                        name = "Named at release",
                        description = "Described at release",
                        attributes = emptyList<AttributeAssignment>(),
                        keywords = setOf("frozen-keyword"),
                    ).execute()
                    CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand theme").execute()
                    ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.0.0", notes = "the first").execute()

                    // Everything the release recorded, changed afterwards.
                    UpdateCatalogMetadata(
                        tenantKey = tenant.id,
                        catalogKey = catalogKey,
                        name = "Renamed since",
                        description = "Described differently since",
                        attributes = emptyList<AttributeAssignment>(),
                        keywords = setOf("later-keyword"),
                    ).execute()
                }
            }
        }

        val body = restTemplate
            .getForEntity("/tenants/${tenant.id.value}/catalogs/detail-cat/releases/1.0.0", String::class.java)
            .body!!

        assertThat(body).contains("Named at release", "Described at release", "frozen-keyword", "the first")
        assertThat(body)
            .`as`("the live catalog has moved on and must not leak onto a release page")
            .doesNotContain("Renamed since", "Described differently since", "later-keyword")
        // The resources are the release's own, listed from what it retained.
        assertThat(body).contains("Brand theme", "brand")
    }
}
