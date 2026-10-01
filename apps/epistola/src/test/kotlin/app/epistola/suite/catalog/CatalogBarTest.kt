// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * Every page that edits a catalog resource says which catalog it is in, and where that catalog
 * stands.
 *
 * It used to say neither. The back link went to a flat list, and the only mention of a catalog was
 * "belongs to a subscribed catalog" — which did not name it. Someone could edit for an hour without
 * knowing whether their work was in a released version or waiting to be.
 *
 * The bar is one fragment on purpose, so the answer is worded the same everywhere; this test is the
 * reason a fifth resource page cannot quietly be added without it.
 *
 * All of this is behind `catalog-context`, default off, so every test here turns it on — and one
 * asserts what an installation that has not opted in sees, which is the pages exactly as before.
 */
class CatalogBarTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `an unreleased edit is named on the template page, with the release action`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Template Context")
                withMediator {
                    val catalogKey = CatalogKey.of("ctx-cat")
                    val catalog = CatalogId(catalogKey, TenantId(tenant.id))
                    SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_CONTEXT, enabled = true).execute()
                    CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Context cat").execute()
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice")
                        .execute().withRequiredDataExample()
                    ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.0.0").execute()
                    // Edited after releasing, which is the state the bar exists to report.
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("later"), catalog), "Later")
                        .execute().withRequiredDataExample()
                }
            }
        }

        val body = get(tenant, "/templates/ctx-cat/invoice")

        // The bar names the catalog and links to it, separately from the header, which is about
        // the template.
        assertThat(body).contains("catalog-bar")
        assertThat(body).contains("Context cat")
        assertThat(body).contains("catalogs/ctx-cat/browse")
        // The one place the summary is checked verbatim: that the query's sentence reaches the
        // markup unaltered. Its content is GetCatalogContextTest's to pin.
        assertThat(body).contains("Authored catalog · last released v1.0.0")
        // Carried by the badge, and only there: the summary used to say it too.
        assertThat(body.split("unreleased changes").size - 1)
            .`as`("said once, not twice")
            .isEqualTo(1)
        assertThat(body)
            .`as`("releasing is offered where there is something to release")
            .contains("Release catalog")
        // The button targets this mount. Rendering the trigger without it is the failure that
        // looks like a broken button: HTMX fires, finds nothing to swap into, and does nothing.
        assertThat(body).contains("id=\"release-dialog-container\"")
    }

    @Test
    fun `a template in a catalog with nothing to release is not offered one`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Template Context Clean")
                withMediator {
                    val catalogKey = CatalogKey.of("clean-cat")
                    val catalog = CatalogId(catalogKey, TenantId(tenant.id))
                    SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_CONTEXT, enabled = true).execute()
                    CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Clean cat").execute()
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice")
                        .execute().withRequiredDataExample()
                    ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.0.0").execute()
                }
            }
        }

        val body = get(tenant, "/templates/clean-cat/invoice")

        assertThat(body).contains("Authored catalog · last released v1.0.0")
        assertThat(body).doesNotContain("unreleased changes")
        assertThat(body)
            .`as`("nothing has changed, so there is no release to cut")
            .doesNotContain("Release catalog")
    }

    @Test
    fun `every catalog resource page carries the bar, worded the same, with a working mount`() {
        lateinit var tenant: Tenant
        val catalogKey = CatalogKey.of("bar-cat")
        fixture {
            given {
                tenant = tenant("Bar Everywhere")
                withMediator {
                    val catalog = CatalogId(catalogKey, TenantId(tenant.id))
                    SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_CONTEXT, enabled = true).execute()
                    CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Bar cat").execute()
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice")
                        .execute().withRequiredDataExample()
                    CreateStencil(id = StencilId(StencilKey.of("address"), catalog), name = "Address").execute()
                    CreateTheme(id = ThemeId(ThemeKey.of("house"), catalog), name = "House").execute()
                    CreateCodeList(
                        id = CodeListId(CodeListKey.of("locales"), catalog),
                        displayName = "Locales",
                        sourceType = CodeListSource.INLINE,
                        entries = listOf(CodeListEntry("en", "English")),
                    ).execute()
                    ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "2.1.0").execute()
                }
            }
        }

        val pages = mapOf(
            "template" to "/templates/bar-cat/invoice",
            "stencil" to "/stencils/bar-cat/address",
            "theme" to "/themes/bar-cat/house",
            "code list" to "/code-lists/bar-cat/locales",
        )

        pages.forEach { (resource, path) ->
            val body = get(tenant, path)
            assertThat(body).`as`("the $resource page carries the bar").contains("catalog-bar")
            // Catalog before resource: the bar introduces the page rather than interrupting it, and
            // its release action stays clear of the header's actions for the resource itself.
            assertThat(body.indexOf("class=\"catalog-bar\""))
                .`as`("the $resource page puts the bar above the page header")
                .isLessThan(body.indexOf("class=\"page-header\""))
            assertThat(body).`as`("the $resource page names the catalog").contains("Bar cat")
            // The state reaches the markup, and says which version. What it *says* is the query's
            // to decide and GetCatalogContextTest's to pin — repeating the sentence on four pages
            // would make rewording it a five-test edit for no extra cover.
            assertThat(body).`as`("the $resource page carries the release state").contains("catalog-bar-state")
            assertThat(body).`as`("the $resource page names the released version").contains("v2.1.0")
            // Nothing has changed since the release, so no page offers one — but every page still
            // carries the mount, because the offer appears the moment something is edited.
            assertThat(body).`as`("the $resource page has nothing to release").doesNotContain("Release catalog")
            assertThat(body)
                .`as`("the $resource page mounts the release dialog")
                .contains("id=\"release-dialog-container\"")
        }
    }

    /**
     * The default. `catalog-context` is alpha, so an installation that has not switched it on gets
     * the pages it had before — not an empty bar, not a stray mount, nothing.
     *
     * The gate lives in `GetCatalogContext`, which returns null while the feature is off, so this
     * covers all four pages at once: none of them can render a bar without a context.
     */
    @Test
    fun `with the feature off, no resource page shows a bar`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Bar Off")
                withMediator {
                    val catalogKey = CatalogKey.of("off-cat")
                    val catalog = CatalogId(catalogKey, TenantId(tenant.id))
                    CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Off cat").execute()
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice")
                        .execute().withRequiredDataExample()
                    CreateStencil(id = StencilId(StencilKey.of("address"), catalog), name = "Address").execute()
                    CreateTheme(id = ThemeId(ThemeKey.of("house"), catalog), name = "House").execute()
                    // Released and then edited: the state that would produce the loudest bar.
                    ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.0.0").execute()
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("later"), catalog), "Later")
                        .execute().withRequiredDataExample()
                }
            }
        }

        listOf("/templates/off-cat/invoice", "/stencils/off-cat/address", "/themes/off-cat/house").forEach { path ->
            val body = get(tenant, path)
            assertThat(body).`as`("$path renders no bar").doesNotContain("class=\"catalog-bar\"")
            assertThat(body).`as`("$path offers no release").doesNotContain("Release catalog")
            assertThat(body).`as`("$path mounts no release dialog").doesNotContain("id=\"release-dialog-container\"")
        }
    }

    private fun get(tenant: Tenant, path: String): String = restTemplate
        .getForEntity("/tenants/${tenant.id.value}$path", String::class.java)
        .body!!
}
