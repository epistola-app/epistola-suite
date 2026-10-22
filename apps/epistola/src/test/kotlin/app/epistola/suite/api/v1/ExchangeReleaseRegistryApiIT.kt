// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.commands.ChangeCatalogNamespace
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.exchange.CompleteExchangeConnection
import app.epistola.suite.exchange.StartExchangeConnection
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SharedFakeExchange
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.themes.commands.CreateTheme
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
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
 * Release registries over REST (contract ADR 0008, alpha), the Exchange kind, against the shared
 * fake Exchange: listed for a connected tenant, its releases listed and installed, a release
 * published to it queued (again too), and a namespace it does not grant refused.
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
@ResourceLock(SharedFakeExchange.RESOURCE_LOCK)
class ExchangeReleaseRegistryApiIT : IntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.endpoint") { SharedFakeExchange.server.baseUrl }
            registry.add("epistola.exchange.allow-http") { "true" }
        }
    }

    private val exchange: FakeExchangeServer get() = SharedFakeExchange.server

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: Jdbi

    /** The outbox is installation-wide and the fake is shared: each test starts from neither. */
    @BeforeEach
    fun purgeSharedExchangeState() {
        exchange.reset()
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM catalog_release_publication_archives")
            handle.execute("DELETE FROM catalog_release_publications")
        }
    }

    @Test
    fun `a connected tenant has the Exchange, and installs a release from it`() {
        val (unconnected, keyU) = seedTenantAndKey()
        val (tenant, key) = seedTenantAndKey().also { connect(it.first) }
        assertThat(JsonPath.read<List<String>>(call(HttpMethod.GET, "/api/tenants/${unconnected.value}/release-registries", keyU).body!!, "$.items")).isEmpty()

        val registries = call(HttpMethod.GET, "/api/tenants/${tenant.value}/release-registries", key)
        assertThat(registries.statusCode).`as`(registries.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(registries.body!!, "$.items[*].name")).containsExactly("exchange")
        assertThat(JsonPath.read<List<String>>(registries.body!!, "$.items[*].kind")).containsExactly("exchange")

        exchange.publish("acme", "rr-letters", releaseArchive("rr-letters"), version = "1.0.0")
        val releases = "/api/tenants/${tenant.value}/release-registries/exchange/catalogs/acme/rr-letters/releases"
        val listed = call(HttpMethod.GET, releases, key)
        assertThat(listed.statusCode).`as`(listed.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(listed.body!!, "$.items[*].version")).containsExactly("1.0.0")

        val installed = call(HttpMethod.POST, "$releases/1.0.0/install", key)
        assertThat(installed.statusCode).`as`(installed.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(installed.body!!, "$.namespace")).isEqualTo("acme")
        assertThat(JsonPath.read<Boolean>(installed.body!!, "$.newlyInstalled")).isTrue()

        assertProblem(call(HttpMethod.POST, "$releases/2.0.0/install", key), HttpStatus.NOT_FOUND, "not-found")
    }

    @Test
    fun `a release published to the Exchange is queued, again too, and an ungranted namespace is refused`() {
        val (tenant, key) = seedTenantAndKey().also { connect(it.first) }
        withMediator {
            CreateCatalog(tenant, CatalogKey.of("rr-granted"), "Granted").execute()
            ChangeCatalogNamespace(tenant, CatalogKey.of("rr-granted"), "public-services").execute()
            ReleaseCatalogDraft(tenant, CatalogKey.of("rr-granted"), "1.0.0", publication = ReleasePublication.SKIP).execute()
            CreateCatalog(tenant, CatalogKey.of("rr-other"), "Other", namespace = "not-granted").execute()
            ReleaseCatalogDraft(tenant, CatalogKey.of("rr-other"), "1.0.0", publication = ReleasePublication.SKIP).execute()
        }
        val publish = withMediator { "${CatalogApiPaths.version(tenant, "rr-granted", "1.0.0")}/publish" }

        val queued = call(HttpMethod.POST, publish, key, """{"to": "exchange"}""")
        assertThat(queued.statusCode).`as`(queued.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(queued.body!!, "$.status")).isEqualTo("queued")
        assertThat(JsonPath.read<String>(queued.body!!, "$.registry")).isEqualTo("exchange")
        val again = call(HttpMethod.POST, publish, key, """{"to": "exchange"}""")
        assertThat(again.statusCode).`as`(again.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(again.body!!, "$.status")).isEqualTo("queued")

        val other = withMediator { "${CatalogApiPaths.version(tenant, "rr-other", "1.0.0")}/publish" }
        assertProblem(call(HttpMethod.POST, other, key, """{"to": "exchange"}"""), HttpStatus.CONFLICT, "namespace-not-granted")
    }

    private fun connect(tenant: TenantKey) = withMediator {
        SaveFeatureToggle(tenant, KnownFeatures.CATALOG_INSTALLING, true).execute()
        SaveFeatureToggle(tenant, KnownFeatures.CATALOG_PUBLISHING, true).execute()
        StartExchangeConnection(tenant, "https://suite.example/oauth/exchange/callback").execute()
        CompleteExchangeConnection(tenant, requireNotNull(exchange.latestState.get()), "authorization-code", FakeExchangeServer.OAUTH_APPLICATION_ID, exchange.baseUrl).execute()
    }

    /** Release 1.0.0 of [catalog] in namespace `acme`, as its publisher exports it. */
    private fun releaseArchive(catalog: String): ByteArray {
        val (publisher, _) = seedTenantAndKey()
        val key = CatalogKey.of(catalog)
        return withMediator {
            CreateCatalog(publisher, key, "Letters", namespace = "acme").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("thm"), CatalogId(key, TenantId(publisher))), name = "Theme").execute()
            MarkCatalogReady(publisher, key).execute()
            ReleaseCatalogDraft(tenantKey = publisher, catalogKey = key, version = "1.0.0").execute()
            mediator.exportReleased(CatalogId(key, TenantId(publisher))).zipBytes
        }
    }

    private fun assertProblem(response: ResponseEntity<String>, status: HttpStatus, slug: String) {
        assertThat(response.statusCode).`as`(response.body).isEqualTo(status)
        assertThat(JsonPath.read<String>(response.body!!, "$.type")).isEqualTo("https://epistola.app/errors/$slug")
    }

    private fun call(method: HttpMethod, url: String, apiKey: String, body: String? = null): ResponseEntity<String> = restTemplate.exchange(url, method, HttpEntity(body, headers(apiKey)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.5.0 exchange-release-registry-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("xreg-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Exchange Registry Tenant").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "exchange-registry-it").execute().plaintextKey
    }
}
