// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SharedRegistries
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.themes.commands.CreateTheme
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * Release registries over REST (contract ADR 0008, alpha), the container kind, against real
 * `registry:2` registries: two tenants play two installations, A publishes and B installs.
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    CollectSmokeSecurityConfig::class,
)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
        "epistola.generation.polling.enabled=false",
    ],
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ReleaseRegistriesApiIT : IntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registries(registry: DynamicPropertyRegistry) = SharedRegistries.register(registry)
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the configured container registries are listed by name and kind`() {
        val (tenant, key) = seedTenantAndKey()

        val response = call(HttpMethod.GET, "/api/tenants/${tenant.value}/release-registries", key)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(response.body!!, "$.items[*].name")).containsExactly("local", "secured")
        assertThat(JsonPath.read<List<String>>(response.body!!, "$.items[*].kind")).containsExactly("container", "container")
    }

    @Test
    fun `a release is published, listed, and installed in another tenant`() {
        val (a, keyA) = seedTenantAndKey()
        val (b, keyB) = seedTenantAndKey()
        val catalog = "rr-${UUID.randomUUID().toString().take(8)}"
        val fingerprint = releaseFirst(a, catalog)
        val publish = withMediator { "${CatalogApiPaths.version(a, catalog, "1.0.0")}/publish" }
        val namespace = withMediator { CatalogApiPaths.namespace(a, catalog) }

        val published = call(HttpMethod.POST, publish, keyA, """{"to": "local"}""")
        assertThat(published.statusCode).`as`(published.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(published.body!!, "$.status")).isEqualTo("published")
        assertThat(JsonPath.read<String>(published.body!!, "$.releaseRegistry")).isEqualTo("local")
        assertThat(JsonPath.read<String>(published.body!!, "$.version")).isEqualTo("1.0.0")
        assertThat(JsonPath.read<String>(published.body!!, "$.digest")).startsWith("sha256:")

        val again = call(HttpMethod.POST, publish, keyA, """{"to": "local"}""")
        assertThat(JsonPath.read<String>(again.body!!, "$.status")).isEqualTo("already-published")

        val releases = "/api/tenants/${b.value}/release-registries/local/catalogs/~/$namespace/$catalog/releases"
        val listed = call(HttpMethod.GET, releases, keyB)
        assertThat(listed.statusCode).`as`(listed.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(listed.body!!, "$.releaseRegistry")).isEqualTo("local")
        assertThat(JsonPath.read<String>(listed.body!!, "$.registry")).`as`("the catalog's authority").isEqualTo("exchange.epistola.app")
        assertThat(JsonPath.read<List<String>>(listed.body!!, "$.items[*].version")).containsExactly("1.0.0")
        assertThat(JsonPath.read<List<String>>(listed.body!!, "$.items[*].fingerprint")).containsExactly(fingerprint)

        val installed = call(HttpMethod.POST, "$releases/1.0.0/install", keyB)
        assertThat(installed.statusCode).`as`(installed.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(installed.body!!, "$.catalogKey")).isEqualTo(catalog)
        assertThat(JsonPath.read<String>(installed.body!!, "$.namespace")).isEqualTo(namespace)
        assertThat(JsonPath.read<Boolean>(installed.body!!, "$.newlyInstalled")).isTrue()
        assertThat(withMediator { ListCatalogReleaseSummaries(b, CatalogKey.of(catalog)).query().single().fingerprint }).isEqualTo(fingerprint)

        val repeated = call(HttpMethod.POST, "$releases/1.0.0/install", keyB)
        assertThat(JsonPath.read<Boolean>(repeated.body!!, "$.newlyInstalled")).isFalse()
    }

    @Test
    fun `a catalog under local is published to a container registry under its full name and installed elsewhere`() {
        val (a, keyA) = seedTenantAndKey()
        val (b, keyB) = seedTenantAndKey()
        val catalog = "rr-${UUID.randomUUID().toString().take(8)}"
        val key = CatalogKey.of(catalog)
        withMediator {
            CreateCatalog(tenantKey = a, id = key, name = catalog, registry = "local", namespace = "personnel").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(a))), name = "Brand").execute()
            MarkCatalogReady(a, key).execute()
            ReleaseCatalogDraft(tenantKey = a, catalogKey = key, version = "1.0.0").execute()
        }

        val published = call(HttpMethod.POST, "/api/tenants/${a.value}/catalogs/local/personnel/$catalog/releases/1.0.0/publish", keyA, """{"to": "local"}""")
        assertThat(published.statusCode).`as`(published.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(published.body!!, "$.status")).isEqualTo("published")

        val releases = "/api/tenants/${b.value}/release-registries/local/catalogs/local/personnel/$catalog/releases"
        val listed = call(HttpMethod.GET, releases, keyB)
        assertThat(listed.statusCode).`as`(listed.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(listed.body!!, "$.registry")).isEqualTo("local")
        assertThat(JsonPath.read<List<String>>(listed.body!!, "$.items[*].version")).containsExactly("1.0.0")

        val installed = call(HttpMethod.POST, "$releases/1.0.0/install", keyB)
        assertThat(installed.statusCode).`as`(installed.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(installed.body!!, "$.registry")).isEqualTo("local")
        assertThat(JsonPath.read<String>(installed.body!!, "$.namespace")).isEqualTo("personnel")
        assertThat(withMediator { GetCatalog(b, key).query()!!.registry }).isEqualTo("local")
    }

    @Test
    fun `refusals name the contract's problem types`() {
        val (a, keyA) = seedTenantAndKey()
        val catalog = "rr-${UUID.randomUUID().toString().take(8)}"
        releaseFirst(a, catalog)
        val namespace = withMediator { CatalogApiPaths.namespace(a, catalog) }
        val release = withMediator { CatalogApiPaths.version(a, catalog, "1.0.0") }

        val unknown = call(HttpMethod.GET, "/api/tenants/${a.value}/release-registries/nowhere/catalogs/~/$namespace/$catalog/releases", keyA)
        assertProblem(unknown, HttpStatus.NOT_FOUND, "release-registry-not-found")
        assertProblem(call(HttpMethod.POST, "$release/publish", keyA, """{"to": "nowhere"}"""), HttpStatus.NOT_FOUND, "release-registry-not-found")

        val notHeld = call(HttpMethod.POST, "/api/tenants/${a.value}/release-registries/local/catalogs/~/$namespace/$catalog/releases/9.9.9/install", keyA)
        assertProblem(notHeld, HttpStatus.NOT_FOUND, "not-found")

        val draft = withMediator { CatalogApiPaths.version(a, catalog, "next") }
        assertProblem(call(HttpMethod.POST, "$draft/publish", keyA, """{"to": "local"}"""), HttpStatus.CONFLICT, "conflict")

        // Another installation's 1.0.0 of the same catalog, with other content, is in the registry
        // first: one number names one release.
        val (c, keyC) = seedTenantAndKey()
        withMediator {
            val key = CatalogKey.of(catalog)
            // Two installations of one organisation share the namespace.
            TestNamespaces.grant(c, namespace).execute()
            CreateCatalog(tenantKey = c, id = key, name = catalog, namespace = namespace).execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(c))), name = "Another brand").execute()
            MarkCatalogReady(c, key).execute()
            ReleaseCatalogDraft(tenantKey = c, catalogKey = key, version = "1.0.0").execute()
        }
        val theirs = withMediator { "${CatalogApiPaths.version(c, catalog, "1.0.0")}/publish" }
        assertThat(call(HttpMethod.POST, theirs, keyC, """{"to": "local"}""").statusCode).isEqualTo(HttpStatus.OK)
        assertProblem(call(HttpMethod.POST, "$release/publish", keyA, """{"to": "local"}"""), HttpStatus.CONFLICT, "release-number-taken")
    }

    private fun assertProblem(response: ResponseEntity<String>, status: HttpStatus, slug: String) {
        assertThat(response.statusCode).`as`(response.body).isEqualTo(status)
        assertThat(JsonPath.read<String>(response.body!!, "$.type")).isEqualTo("https://epistola.app/errors/$slug")
    }

    /** Catalog [catalog] of [tenant], holding theme `brand`, released as 1.0.0; its fingerprint. */
    private fun releaseFirst(tenant: TenantKey, catalog: String): String = withMediator {
        val key = CatalogKey.of(catalog)
        CreateCatalog(tenantKey = tenant, id = key, name = catalog).execute()
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant))), name = "Brand").execute()
        MarkCatalogReady(tenant, key).execute()
        ReleaseCatalogDraft(tenantKey = tenant, catalogKey = key, version = "1.0.0").execute().fingerprint
    }

    private fun call(method: HttpMethod, url: String, apiKey: String, body: String? = null): ResponseEntity<String> = restTemplate.exchange(url, method, HttpEntity(body, headers(apiKey)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.5.0 release-registries-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("rreg-${UUID.randomUUID().toString().take(8)}")
        TestNamespaces.createTenant(tenantKey, "Release Registries Tenant").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "release-registries-it").execute().plaintextKey
    }
}
