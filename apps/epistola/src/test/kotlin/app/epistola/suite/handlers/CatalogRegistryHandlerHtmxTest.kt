// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CheckCatalogUpstream
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.registry.InstallReleaseFromRegistry
import app.epistola.suite.catalog.registry.PublishReleaseToRegistry
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.SharedRegistries
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap

/** The registry route in the UI (alpha), against real `registry:2` registries. */
class CatalogRegistryHandlerHtmxTest : BaseIntegrationTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registries(registry: DynamicPropertyRegistry) = SharedRegistries.register(registry)
    }

    @Autowired private lateinit var restTemplate: TestRestTemplate

    private fun htmx() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun htmxForm() = htmx().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }

    private fun form(vararg pairs: Pair<String, String>) = LinkedMultiValueMap<String, String>().apply { pairs.forEach { (k, v) -> add(k, v) } }

    private fun get(url: String) = restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(htmx()), String::class.java)

    private fun post(url: String, vararg pairs: Pair<String, String>) = restTemplate.postForEntity(url, HttpEntity(form(*pairs), htmxForm()), String::class.java)

    /** A tenant with catalog [key] released as 1.0.0, and its namespace. */
    private fun released(name: String, key: String): Pair<TenantKey, String> = withMediator {
        val tenant = createTenant(name).id
        val catalog = CatalogKey.of(key)
        CreateCatalog(tenantKey = tenant, id = catalog, name = name).execute()
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(catalog, TenantId(tenant))), name = "Brand").execute()
        MarkCatalogReady(tenant, catalog).execute()
        ReleaseCatalogVersion(tenantKey = tenant, catalogKey = catalog, version = "1.0.0").execute()
        tenant to GetCatalog(tenant, catalog).query()!!.namespace
    }

    @Test
    fun `the catalogs page offers a registry, and the dialog asks for namespace and ID`() {
        val tenant = createTenant("Registry UI page").id

        val page = restTemplate.getForEntity("/tenants/${tenant.value}/catalogs", String::class.java)
        assertThat(page.body).contains("data-testid=\"catalog-registry-open\"")

        val dialog = get("/tenants/${tenant.value}/catalogs/registry")
        assertThat(dialog.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(dialog.body).contains("id=\"registry-get-dialog\"", "badge-alpha", "name=\"namespace\"", "name=\"catalogKey\"")
            .contains("data-testid=\"registry-name\"") // two registries configured: a choice

        val direct = restTemplate.getForEntity("/tenants/${tenant.value}/catalogs/registry", String::class.java)
        assertThat(direct.body).`as`("opened directly: the list with the dialog in it").contains("id=\"catalog-list\"", "id=\"registry-get-dialog\"")
    }

    @Test
    fun `versions are listed and one is installed into the tenant`() {
        val (a, namespace) = released("Registry UI source", "ui-registry")
        withMediator { PublishReleaseToRegistry(a, CatalogKey.of("ui-registry"), "1.0.0", "local").execute() }
        val b = createTenant("Registry UI target").id

        val listed = get("/tenants/${b.value}/catalogs/registry/releases?registry=local&namespace=$namespace&catalogKey=ui-registry")
        assertThat(listed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(listed.body).contains("id=\"registry-install-form\"", "value=\"1.0.0\"")

        val installed = post("/tenants/${b.value}/catalogs/registry/install", "registry" to "local", "namespace" to namespace, "catalogKey" to "ui-registry", "version" to "1.0.0")
        assertThat(installed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(installed.headers.getFirst("HX-Trigger")).contains("closeDialog")
        assertThat(installed.body).contains("ui-registry")
        assertThat(withMediator { ListCatalogReleases(b, CatalogKey.of("ui-registry")).query().map { it.version } }).containsExactly("1.0.0")

        val again = get("/tenants/${b.value}/catalogs/registry/releases?registry=local&namespace=$namespace&catalogKey=ui-registry")
        assertThat(again.body).contains("registry-all-present").doesNotContain("id=\"registry-install-form\"")
    }

    @Test
    fun `a newer version in the registry is offered from the catalog list through the registry dialog`() {
        val (a, namespace) = released("Registry UI update", "ui-update")
        val key = CatalogKey.of("ui-update")
        val b = createTenant("Registry UI update target").id
        withMediator {
            PublishReleaseToRegistry(a, key, "1.0.0", "local").execute()
            InstallReleaseFromRegistry(b, "local", namespace, "ui-update", "1.0.0").execute()
            UpdateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(a))), name = "Brand 1.1").execute()
            MarkCatalogReady(a, key).execute()
            ReleaseCatalogVersion(tenantKey = a, catalogKey = key, version = "1.1.0").execute()
            PublishReleaseToRegistry(a, key, "1.1.0", "local").execute()
            CheckCatalogUpstream(b, key).execute()
        }

        val list = restTemplate.getForEntity("/tenants/${b.value}/catalogs", String::class.java).body!!
        val review = Regex("""<button[^>]*data-testid="catalog-upgrade-review"[^>]*>""").find(list)?.value
        assertThat(review).`as`("the update button").isNotNull()
        assertThat(review).contains("/catalogs/registry?registry=local&amp;namespace=$namespace&amp;catalogKey=ui-update", "hx-target=\"#dialog-mount\"")
        assertThat(list).`as`("no manifest check for a registry catalog").doesNotContain("/catalogs/ui-update/upgrade-check")
    }

    @Test
    fun `a catalog the registry does not hold says so, and an install refusal lands in the dialog`() {
        val tenant = createTenant("Registry UI missing").id

        val listed = get("/tenants/${tenant.value}/catalogs/registry/releases?registry=local&namespace=nobody&catalogKey=nothing-here")
        assertThat(listed.body).contains("registry-no-releases")

        val refused = post("/tenants/${tenant.value}/catalogs/registry/install", "registry" to "local", "namespace" to "nobody", "catalogKey" to "nothing-here", "version" to "1.0.0")
        assertThat(refused.statusCode.value()).isEqualTo(422)
        assertThat(refused.body).contains("registry-install-error", "has no release 1.0.0")
    }

    @Test
    fun `a release page offers publishing, and publishing says where it went, once or again`() {
        val (a, namespace) = released("Registry UI publish", "ui-publish")

        val page = restTemplate.getForEntity("/tenants/${a.value}/catalogs/ui-publish/releases/1.0.0", String::class.java)
        assertThat(page.body).contains("data-testid=\"release-registry-publish\"")

        val dialog = get("/tenants/${a.value}/catalogs/ui-publish/registry-publish?version=1.0.0")
        assertThat(dialog.body).contains("id=\"registry-publish-dialog\"", "Publish v1.0.0", "badge-alpha")

        val first = post("/tenants/${a.value}/catalogs/ui-publish/registry-publish", "version" to "1.0.0", "registry" to "local")
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(first.body).contains("Published.", "/$namespace/ui-publish:1.0.0")

        val second = post("/tenants/${a.value}/catalogs/ui-publish/registry-publish", "version" to "1.0.0", "registry" to "local")
        assertThat(second.body).contains("had this version already")
    }
}

/** Without a registry configured, nothing of the registry route is offered. */
class CatalogRegistryAbsentHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `no registry configured, no registry controls`() {
        val tenant = withMediator {
            val t = createTenant("Registry UI absent").id
            CreateCatalog(tenantKey = t, id = CatalogKey.of("no-registry"), name = "No registry").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(CatalogKey.of("no-registry"), TenantId(t))), name = "Brand").execute()
            MarkCatalogReady(t, CatalogKey.of("no-registry")).execute()
            ReleaseCatalogVersion(tenantKey = t, catalogKey = CatalogKey.of("no-registry"), version = "1.0.0").execute()
            t
        }

        assertThat(restTemplate.getForEntity("/tenants/${tenant.value}/catalogs", String::class.java).body).doesNotContain("catalog-registry-open")
        assertThat(restTemplate.getForEntity("/tenants/${tenant.value}/catalogs/no-registry/releases/1.0.0", String::class.java).body)
            .doesNotContain("release-registry-publish")
    }
}
