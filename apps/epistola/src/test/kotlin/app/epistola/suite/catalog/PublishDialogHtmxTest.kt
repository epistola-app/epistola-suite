// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/**
 * Publish, for people who are not technical (docs/catalog-workspace-navigation.md): the dialog says
 * what changes and what that means, picks the number from the kind of change, and marks the changes
 * ready itself; it offers nothing when there is nothing to publish.
 */
class PublishDialogHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    private val letters = CatalogKey.of("letters")

    /** Letters published as 1.0.0 with a code list (en, nl); its work is 1.0.1. */
    private fun published(name: String): Tenant {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant(name)
                withMediator {
                    CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
                    CreateCodeList(
                        id = codeList(tenant),
                        displayName = "Languages",
                        sourceType = CodeListSource.INLINE,
                        entries = listOf(CodeListEntry("en", "English"), CodeListEntry("nl", "Dutch")),
                    ).execute()
                    mediator.releaseNext(testCatalog(TenantId(tenant.id), letters))
                }
            }
        }
        return tenant
    }

    private fun codeList(tenant: Tenant) = CodeListId(CodeListKey.of("languages"), testCatalog(TenantId(tenant.id), letters))

    @Test
    fun `an addition is named, said to keep everything working, and published as the next minor`() {
        val tenant = published("Publish Additive")
        withMediator {
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant.id), letters)), "Invoice")
                .execute().withRequiredDataExample()
        }

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!

        assertThat(dialog).contains(">Publish Letters<")
        assertThat(dialog).contains(">Added<").contains("<strong>Invoice</strong>")
        assertThat(dialog).`as`("grouped by kind, with a summary in the header")
            .contains("data-testid=\"publish-change-group\"").contains("<span>Templates</span>")
            .contains("data-testid=\"publish-summary\">1 change: 1 added.<")
        assertThat(dialog).`as`("the form kind: sticky header and footer, Publish in view")
            .contains("class=\"ep-dialog release-dialog\" data-dialog=\"form\"")
        assertThat(dialog).contains("data-testid=\"publish-impact-additive\"")
        assertThat(dialog).contains("It will be published as <strong>v1.1.0</strong>")
        assertThat(dialog).contains("data-testid=\"release-submit\"")
        assertThat(dialog).`as`("ready marks are not the person's problem").doesNotContain("data-testid=\"release-blocked\"")
    }

    @Test
    fun `a breaking change is warned about with its reason, and published as the next major`() {
        val tenant = published("Publish Breaking")
        withMediator {
            UpdateCodeList(codeList(tenant), displayName = "Languages", entries = listOf(CodeListEntry("en", "English"))).execute()
        }

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!

        assertThat(dialog).contains("data-testid=\"publish-impact-breaking\"").contains("This can break what is connected to it")
        assertThat(dialog).contains("Code list Languages: entry &#39;nl&#39; removed")
        assertThat(dialog).contains("It will be published as <strong>v2.0.0</strong>")
    }

    @Test
    fun `with nothing changed there is nothing to publish`() {
        val tenant = published("Publish Nothing")

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!

        assertThat(dialog).contains("data-testid=\"publish-nothing\"")
        assertThat(dialog).doesNotContain("data-testid=\"release-submit\"")
    }

    @Test
    fun `publishing with nothing changed is refused, so new notes alone are no new version`() {
        val tenant = published("Publish Notes Only")

        val response = postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.0.1", "notes" to "Only new notes"))

        val released = withMediator { ListCatalogReleases(testCatalog(tenant.id, letters), CatalogReleaseStatus.RELEASED).query().map { it.version } }
        assertThat(released).containsExactly("1.0.0")
        assertThat(response.body).contains("has nothing to release")
    }

    @Test
    fun `publishing marks the changes ready itself and publishes under the picked number`() {
        val tenant = published("Publish Submit")
        withMediator {
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant.id), letters)), "Invoice")
                .execute().withRequiredDataExample()
        }

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.1.0"))

        val released = withMediator { ListCatalogReleases(testCatalog(tenant.id, letters), CatalogReleaseStatus.RELEASED).query().map { it.version } }
        assertThat(released).contains("1.1.0")
    }

    @Test
    fun `publishing makes the new version live in the environment ticked, so a changed template reaches the application in one go`() {
        val tenant = published("Publish Live")
        withMediator {
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), "Test").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant.id), letters)), "Invoice")
                .execute().withRequiredDataExample()
        }

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!
        assertThat(dialog).contains("data-testid=\"publish-live\"")
        assertThat(dialog.substringAfter("data-environment=\"test\"").substringBefore("</label>"))
            .`as`("the only environment is ticked").contains("checked")

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.1.0", "liveIn" to "test"))

        val live = withMediator { ListCatalogReleases(testCatalog(tenant.id, letters), CatalogReleaseStatus.RELEASED).query() }.first { it.version == "1.1.0" }.deployedTo
        assertThat(live).containsExactly("test")
    }

    @Test
    fun `a production environment is never ticked for you, and publishing to it adds beside what it uses unless asked to replace`() {
        val tenant = published("Publish Production")
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
        withMediator {
            CreateEnvironment(production, "Production", production = true).execute()
            DeployCatalogRelease(production, testCatalog(production.tenantKey, letters), "1.0.0").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant.id), letters)), "Invoice")
                .execute().withRequiredDataExample()
        }

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!
        val target = dialog.substringAfter("data-environment=\"production\"").substringBefore("</label>")
        assertThat(target).`as`("the only environment, but production").doesNotContain("checked").contains(">Production</span>")
        assertThat(dialog).contains("data-testid=\"publish-live-replace\"")

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.1.0", "liveIn" to "production"))
        assertThat(withMediator { ListDeployments(tenant.id, testCatalog(tenant.id, letters)).query() }.map { it.version })
            .`as`("beside 1.0.0, which pinned requests keep getting").containsExactlyInAnyOrder("1.1.0", "1.0.0")

        withMediator { CreateDocumentTemplate(TemplateId(TemplateKey.of("reminder"), testCatalog(TenantId(tenant.id), letters)), "Reminder").execute().withRequiredDataExample() }
        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.2.0", "liveIn" to "production", "replaceLive" to "true"))
        assertThat(withMediator { ListDeployments(tenant.id, testCatalog(tenant.id, letters)).query() }.map { it.version }).containsExactly("1.2.0")
    }

    @Test
    fun `with several environments none is ticked, and publishing without one makes nothing live`() {
        val tenant = published("Publish Not Live")
        withMediator {
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), "Test").execute()
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant.id), letters)), "Invoice")
                .execute().withRequiredDataExample()
        }

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!
        assertThat(dialog.substringAfter("data-testid=\"publish-live\"").substringBefore("</div>")).doesNotContain("checked")

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.1.0"))

        val released = withMediator { ListCatalogReleases(testCatalog(tenant.id, letters), CatalogReleaseStatus.RELEASED).query() }.first { it.version == "1.1.0" }
        assertThat(released.deployedTo).isEmpty()
    }

    /** `shared` with a brand theme, published; the invoice in letters uses it without declaring it. */
    private fun usingSharedTheme(tenant: Tenant, publishShared: Boolean = true) = withMediator {
        val shared = CatalogKey.of("shared")
        CreateCatalog(tenantKey = tenant.id, id = shared, name = "Shared").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), shared)), "Brand").execute()
        if (publishShared) mediator.releaseNext(testCatalog(TenantId(tenant.id), shared))
        val invoice = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant.id), letters))
        CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(invoice.tenantKey, shared)).execute()
    }

    /**
     * The number the publish dialog offers, from what changed. Starting to use another catalog adds a
     * dependency: an addition, so a minor, never a break.
     */
    private fun offered(dialog: String): String = Regex("""id="release-version" name="version"[^>]*value="([^"]+)"""").find(dialog)!!.groupValues[1]

    @Test
    fun `a theme picked from another catalog is declared as it is used, and publishing pins the release it holds`() {
        val tenant = published("Publish Borrowed")
        usingSharedTheme(tenant)

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!
        assertThat(dialog).`as`("Shared's next version has nothing to publish").doesNotContain("data-testid=\"publish-along\"")
        assertThat(dialog).contains("data-testid=\"release-submit\"")
        assertThat(offered(dialog)).`as`("a dependency added is an addition").isEqualTo("1.1.0")

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to offered(dialog)))

        assertThat(withMediator { ListCatalogReleases(testCatalog(tenant.id, letters), CatalogReleaseStatus.RELEASED).query() }.map { it.version }).contains("1.1.0")
        assertThat(withMediator { ListReleaseDependencies(testCatalog(tenant.id, letters), "1.1.0").query() }.map { it.catalogKey to it.releaseVersion })
            .containsExactly("shared" to "1.0.0")
    }

    /**
     * What the 2.0 upgrade leaves for what a 1.x catalog used: declarations by name, pinning no
     * release and naming no draft. No command makes one, a declaration names one or the other.
     */
    private fun declaredByName(tenant: Tenant) = jdbi.useHandle<Exception> {
        it.createUpdate("UPDATE catalog_version_dependencies SET release_version = NULL, release_fingerprint = NULL, target_version_id = NULL WHERE tenant_key = :t")
            .bind("t", tenant.id)
            .execute()
    }

    @Test
    fun `a catalog declared by name and never published is published along, not a dead end`() {
        val tenant = published("Publish By Name Unpublished")
        usingSharedTheme(tenant, publishShared = false)
        declaredByName(tenant)

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!

        assertThat(dialog).doesNotContain("data-testid=\"release-blocked-dependencies\"")
        assertThat(dialog).contains("Shared is published too, as v1.0.0: this uses its Next release, with Brand not published yet.")
        assertThat(dialog).contains("data-testid=\"release-submit\"")

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to offered(dialog)))

        assertThat(withMediator { ListReleaseDependencies(testCatalog(tenant.id, letters), offered(dialog)).query() }.map { it.catalogKey to it.releaseVersion })
            .containsExactly("shared" to "1.0.0")
    }

    @Test
    fun `a catalog declared by name with a release is pinned to it on publishing, not a dead end`() {
        val tenant = published("Publish By Name Released")
        usingSharedTheme(tenant)
        declaredByName(tenant)

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!

        assertThat(dialog).doesNotContain("data-testid=\"release-blocked-dependencies\"")
        assertThat(dialog).contains("data-testid=\"release-submit\"")

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to offered(dialog)))

        assertThat(withMediator { ListReleaseDependencies(testCatalog(tenant.id, letters), offered(dialog)).query() }.map { it.catalogKey to it.releaseVersion })
            .containsExactly("shared" to "1.0.0")
    }

    @Test
    fun `a theme from a catalog that was never published has that catalog published along, as its first version`() {
        val tenant = published("Publish Borrowed Unpublished")
        usingSharedTheme(tenant, publishShared = false)

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!

        assertThat(dialog).doesNotContain("data-testid=\"release-blocked-borrowed\"")
        assertThat(dialog).contains("Shared is published too, as v1.0.0: this uses its Next release, with Brand not published yet.")
        assertThat(dialog).contains("data-testid=\"release-submit\"")

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to offered(dialog)))

        assertThat(withMediator { ListReleaseDependencies(testCatalog(tenant.id, letters), offered(dialog)).query() }.map { it.catalogKey to it.releaseVersion })
            .containsExactly("shared" to "1.0.0")
    }

    @Test
    fun `the catalog's Dependencies page says what the next version takes from other catalogs, and what else it could use`() {
        val tenant = published("Publish Borrowed Page")
        usingSharedTheme(tenant)

        val page = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/dependencies", String::class.java).body!!
        val borrowed = page.substringAfter("data-testid=\"catalog-borrowed\"")

        assertThat(borrowed.substringAfter("data-catalog=\"shared\"").substringBefore("</tr>"))
            .contains("Next release, as it is now").contains("Brand").contains("(Theme)")
        assertThat(borrowed).contains("data-testid=\"catalog-borrowed-others\"")
    }

    @Test
    fun `a theme changed in the catalog it comes from is published along, so the result is what was previewed`() {
        val tenant = published("Publish Along")
        usingSharedTheme(tenant)
        withMediator {
            UpdateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), CatalogKey.of("shared"))), documentStyles = mapOf("color" to "#ff0000")).execute()
        }

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!
        val along = dialog.substringAfter("data-testid=\"publish-along\"").substringBefore("data-testid=\"publish-advanced\"")
        assertThat(along).contains("Shared is published too, as v1.0.1: this uses its Next release, with Brand not published yet.")

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to offered(dialog)))

        assertThat(withMediator { ListReleaseDependencies(testCatalog(tenant.id, letters), offered(dialog)).query() }.map { it.catalogKey to it.releaseVersion })
            .containsExactly("shared" to "1.0.1")
    }

    @Test
    fun `a change only in the catalog it uses still offers Publish here, which publishes it along`() {
        val tenant = published("Publish Only Others")
        usingSharedTheme(tenant)
        withMediator {
            mediator.releaseNext(testCatalog(TenantId(tenant.id), letters))
            // Letters' next version now pins Shared 1.0.0; building on Shared's next version again is a choice.
            val target = GetCatalog(testCatalog(tenant.id, CatalogKey.of("shared"))).query()!!
            val sharedNext = GetCatalogDraft(testCatalog(tenant.id, CatalogKey.of("shared"))).query()!!.versionId
            UpdateCatalogDependencies(testCatalog(tenant.id, letters), listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", versionId = sharedNext))).execute()
            UpdateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), CatalogKey.of("shared"))), documentStyles = mapOf("color" to "#ff0000")).execute()
        }

        val page = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/browse", String::class.java).body!!
        assertThat(page).contains("Changes from other catalogs are not published here yet.").contains("From Shared: Brand, not published there yet")
        assertThat(page).contains("data-testid=\"catalog-release-open\"")

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!
        assertThat(dialog).contains("data-testid=\"publish-only-others\"").contains("data-testid=\"release-submit\"")
    }

    private fun getHtmx(url: String): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
        String::class.java,
    )

    private fun postForm(url: String, fields: Map<String, String>): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        val form = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
    }
}
