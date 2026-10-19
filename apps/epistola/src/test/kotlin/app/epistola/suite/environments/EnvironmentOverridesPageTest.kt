// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
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
import java.util.UUID

/**
 * Overrides in the UI: on an environment's page, which release is the latest there, choosing
 * another, and the production mark; on a draft, trying it on an environment, which then follows it.
 */
class EnvironmentOverridesPageTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    /** `letters` released as 1.0.0 and 1.0.1, both served by `test` and by `production` (marked so). */
    private fun setup(slug: String): Tenant {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Overrides $slug") } }
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
            val brand = ThemeId(ThemeKey.of("brand"), CatalogId(letters, TenantId(tenant.id)))
            CreateTheme(brand, "Brand").execute()
            MarkCatalogReady(tenant.id, letters).execute()
            ReleaseCatalogVersion(tenant.id, letters, version = "1.0.0").execute()
            UpdateTheme(brand, name = "Brand, corrected").execute()
            MarkCatalogReady(tenant.id, letters).execute()
            ReleaseCatalogVersion(tenant.id, letters, version = "1.0.1").execute()
            for ((key, production) in listOf("test" to false, "production" to true)) {
                val id = env(tenant, key)
                CreateEnvironment(id, key.replaceFirstChar { it.uppercase() }, production = production).execute()
                DeployRelease(id, letters, "1.0.0").execute()
                DeployRelease(id, letters, "1.0.1").execute()
            }
        }
        return tenant
    }

    private fun env(tenant: Tenant, key: String) = EnvironmentId(EnvironmentKey.of(key), TenantId(tenant.id))

    private fun row(page: String, version: String) = page.substringAfter("data-catalog=\"letters\" data-version=\"$version\"").substringBefore("</tr>")

    @Test
    fun `the environment page marks the latest release, and choosing another makes it the latest there`() {
        val tenant = setup("page-latest")
        val before = page(tenant, "production")
        assertThat(row(before, "1.0.1")).contains("Latest here").doesNotContain("Use as latest")
        assertThat(row(before, "1.0.0")).doesNotContain("Latest here").contains("Use as latest")

        post("/tenants/${tenant.id.value}/environments/production/overrides", "catalog" to "letters", "version" to "1.0.0")

        val after = page(tenant, "production")
        assertThat(row(after, "1.0.0")).contains("Latest here (chosen)").contains("Use the highest again")
        assertThat(row(after, "1.0.1")).doesNotContain("Latest here").contains("Use as latest")
        assertThat(after).contains("Uses Letters v1.0.0 as its latest")

        post("/tenants/${tenant.id.value}/environments/production/overrides/letters/clear")
        assertThat(row(page(tenant, "production"), "1.0.1")).contains("Latest here").doesNotContain("(chosen)")
        assertThat(page(tenant, "production")).contains("Uses the highest Letters release it serves again, not v1.0.0")
    }

    @Test
    fun `the production mark is shown and changed on the page, and refused while it follows work in progress`() {
        val tenant = setup("page-production")
        assertThat(page(tenant, "production")).contains("Only published versions are used here.").contains("Not production")
        assertThat(page(tenant, "test")).contains("Mark as production")

        withMediator { SetEnvironmentOverride(env(tenant, "test"), letters, draft = DraftRef.Next).execute() }
        val refused = post("/tenants/${tenant.id.value}/environments/test/production", "production" to "true")

        assertThat(refused.body).contains("uses work in progress").contains("Next version")
        assertThat(withMediator { GetEnvironment(env(tenant, "test")).query()!! }.production).isFalse()
        assertThat(page(tenant, "test").substringAfter("data-testid=\"environment-following\"")).contains("Letters: Next version").contains("Stop following")

        post("/tenants/${tenant.id.value}/environments/test/overrides/letters/clear")
        post("/tenants/${tenant.id.value}/environments/test/production", "production" to "true")
        assertThat(withMediator { GetEnvironment(env(tenant, "test")).query()!! }.production).isTrue()
    }

    @Test
    fun `a draft is tried on an environment from its version menu, and its banner names who follows it`() {
        val tenant = setup("follow")
        val work: String = withMediator { CreateCatalogDraft(tenant.id, letters, "Redesign").execute().versionId }
        val at = "/tenants/${tenant.id.value}/catalogs/letters@$work"
        assertThat(get("$at/draft").body).contains("data-testid=\"ws-follow\"").contains("Documents there use it as it is changed")

        val dialog = getHtmx("$at/follow").body!!
        assertThat(dialog.substringAfter("data-environment=\"test\"").substringBefore("</tr>")).contains(">Follow<")
        assertThat(dialog.substringAfter("data-environment=\"production\"").substringBefore("</tr>"))
            .`as`("production is listed, not offered").contains("uses only published versions").doesNotContain("follow-toggle")

        val followed = postHtmx("$at/follow", "environment" to "test")

        assertThat(followed.headers["HX-Location"].orEmpty().joinToString()).contains("/catalogs/letters@$work/draft")
        assertThat(withMediator { ListEnvironmentOverrides(tenant.id, EnvironmentKey.of("test")).query() }.single().draftLabel).isEqualTo("Redesign")
        assertThat(get("$at/draft").body).contains("Test follows it.")
        assertThat(getHtmx("$at/follow").body!!.substringAfter("data-environment=\"test\"").substringBefore("</tr>")).contains("Stop following")

        // Production refuses it even when asked directly.
        val refused = postHtmx("$at/follow", "environment" to "production")
        assertThat(refused.body).contains("data-testid=\"follow-error\"").contains("production environment")
        assertThat(refused.statusCode.value()).`as`("a refusal is not a success").isEqualTo(422)
        assertThat(refused.headers.getFirst("HX-Reswap")).isEqualTo("innerHTML")

        postHtmx("$at/unfollow", "environment" to "test")
        assertThat(withMediator { ListEnvironmentOverrides(tenant.id, EnvironmentKey.of("test")).query() }).isEmpty()
        assertThat(get("$at/draft").body).doesNotContain("Test follows it.")
    }

    private fun get(url: String): ResponseEntity<String> = restTemplate.getForEntity(url, String::class.java)

    @Test
    fun `the deployments page lets an environment follow a draft, not production, and stop again`() {
        val tenant = setup("deploy-follow")
        val work: String = withMediator { CreateCatalogDraft(tenant.id, letters, "Redesign").execute().versionId }
        val deployments = "/tenants/${tenant.id.value}/catalogs/letters/deployments"
        fun envRow(page: String, key: String) = page.substringAfter("data-environment=\"$key\"").substringBefore("</tr>")

        val before = get(deployments).body!!
        assertThat(envRow(before, "test")).contains("value=\"draft:$work\"").contains("Redesign (follows its changes)")
        assertThat(envRow(before, "production")).`as`("production uses only published versions").doesNotContain("draft:")

        post(deployments, "environmentId" to "test", "version" to "draft:$work")
        assertThat(envRow(get(deployments).body!!, "test")).contains("data-testid=\"catalog-deployment-follows\"").contains("Uses Redesign")

        post("$deployments/test/unfollow")
        assertThat(envRow(get(deployments).body!!, "test")).doesNotContain("catalog-deployment-follows")
    }

    @Test
    fun `the overview shows each environment's version and the draft it follows, on the draft's box too`() {
        val tenant = setup("overview-follow")
        val work: String = withMediator { CreateCatalogDraft(tenant.id, letters, "Redesign").execute().versionId }
        val overview = "/tenants/${tenant.id.value}/catalogs/letters/browse"
        fun box(page: String, marker: String) = page.substringAfter(marker).substringBefore("catalog-box-actions")

        val before = get(overview).body!!
        assertThat(box(before, "data-environment=\"test\"")).`as`("the highest it serves").contains(">v1.0.1<")

        post("/tenants/${tenant.id.value}/catalogs/letters/deployments", "environmentId" to "test", "version" to "draft:$work")
        val after = get(overview).body!!
        assertThat(after.substringAfter("data-environment=\"test\"")).contains(">Redesign<").contains("Follows this draft")
        assertThat(box(after, "data-draft=\"$work\"")).contains("Used by Test")
    }

    private fun getHtmx(url: String): ResponseEntity<String> = restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }), String::class.java)

    private fun page(tenant: Tenant, environment: String): String = get("/tenants/${tenant.id.value}/environments/$environment").body!!

    private fun post(path: String, vararg fields: Pair<String, String>) = form(path, htmx = false, *fields)

    private fun postHtmx(path: String, vararg fields: Pair<String, String>) = form(path, htmx = true, *fields)

    private fun form(path: String, htmx: Boolean, vararg fields: Pair<String, String>): ResponseEntity<String> = restTemplate.postForEntity(
        path,
        HttpEntity(
            LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } },
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                if (htmx) set("HX-Request", "true")
            },
        ),
        String::class.java,
    )
}
