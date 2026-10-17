// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CreateCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.IntegrationTestBase
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
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

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
class EpistolaCatalogApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `releasing the draft cuts it under its own number and opens the next`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "rel-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)

        val response = restTemplate.exchange(
            "${versionPath(tenantKey, catalogSlug)}/release",
            HttpMethod.POST,
            HttpEntity("""{"notes":"first cut"}""", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.catalogVersion.version")).isEqualTo("1.0.0")
        assertThat(JsonPath.read<String>(body, "$.catalogVersion.status")).isEqualTo("released")
        assertThat(JsonPath.read<String>(body, "$.catalogVersion.fingerprint")).isNotBlank()
        assertThat(JsonPath.read<String>(body, "$.catalogVersion.notes")).isEqualTo("first cut")
        assertThat(JsonPath.read<Boolean>(body, "$.unchangedContent")).isFalse
        assertThat(JsonPath.read<String>(body, "$.openedDraft")).isEqualTo("1.0.1")
    }

    @Test
    fun `release missing catalog returns catalog not found problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val namespace = withMediator { CatalogApiPaths.namespace(tenantKey) }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/~/$namespace/missing-catalog/versions/1.0.0/release",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/catalog-not-found")
        assertThat(JsonPath.read<String>(body, "$.catalogId")).isEqualTo("missing-catalog")
    }

    @Test
    fun `releasing a released version again is refused with actionable problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "rel-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator {
            ReleaseCatalogVersion(
                tenantKey = tenantKey,
                catalogKey = CatalogKey.of(catalogSlug),
                version = "1.0.0",
            ).execute()
        }

        val response = restTemplate.exchange(
            "${versionPath(tenantKey, catalogSlug, "1.0.0")}/release",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/catalog-version-released")
        assertThat(JsonPath.read<String>(body, "$.version")).isEqualTo("1.0.0")
    }

    @Test
    fun `renumbering the draft below a release it contains needs its warnings acknowledged`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "rel-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator {
            ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = CatalogKey.of(catalogSlug), version = "1.0.0").execute()
        }

        val response = restTemplate.exchange(
            versionPath(tenantKey, catalogSlug),
            HttpMethod.PATCH,
            HttpEntity("""{"version":"0.9.0"}""", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/renumber-warnings-not-acknowledged")
        assertThat(JsonPath.read<List<String>>(body, "$.warnings[*].kind")).containsExactly("contains-later-releases")
    }

    /**
     * Several drafts (contract ADR 0002): a draft is opened next to the current one, a write to the
     * draft that is not current lands in that draft only, reads of each answer its own content, and
     * discarding a draft takes only its content. Releasing the lower one leaves the higher behind it,
     * which then releases only once that is acknowledged.
     */
    @Test
    fun `a second draft is opened, the other written to and read back, and discarded`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "drafts-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator { ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = CatalogKey.of(catalogSlug), version = "1.0.0").execute() }
        val versions = "${withMediator { CatalogApiPaths.catalog(tenantKey, catalogSlug) }}/versions"
        fun call(method: HttpMethod, path: String, body: String? = null) = restTemplate.exchange(path, method, HttpEntity(body, baseHeaders(apiKey)), String::class.java)

        val created = call(HttpMethod.POST, versions, """{"version":"2.0.0"}""")
        assertThat(created.statusCode).describedAs(created.body).isEqualTo(HttpStatus.CREATED)
        assertThat(created.headers.location.toString()).endsWith("/versions/2.0.0")
        assertThat(JsonPath.read<String>(created.body!!, "$.status")).isEqualTo("draft")
        assertThat(JsonPath.read<String>(created.body!!, "$.base")).isEqualTo("1.0.0")
        assertThat(call(HttpMethod.POST, versions, """{"version":"2.0.0"}""").statusCode).isEqualTo(HttpStatus.CONFLICT)

        // 2.0.0 is now the current draft; 1.0.1 is written to by its address.
        val written = call(HttpMethod.PATCH, "$versions/1.0.1/themes/tha", """{"name":"Th, fixed"}""")
        assertThat(written.statusCode).describedAs(written.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(call(HttpMethod.GET, "$versions/1.0.1/themes/tha").body!!, "$.name")).isEqualTo("Th, fixed")
        assertThat(JsonPath.read<String>(call(HttpMethod.GET, "$versions/2.0.0/themes/tha").body!!, "$.name")).isEqualTo("Th")
        assertThat(JsonPath.read<List<String>>(call(HttpMethod.GET, versions).body!!, "$.items[*].version")).containsExactly("2.0.0", "1.0.1", "1.0.0")

        assertThat(call(HttpMethod.DELETE, "$versions/2.0.0").statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(call(HttpMethod.GET, "$versions/2.0.0/themes/tha").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<String>(call(HttpMethod.GET, "$versions/1.0.1/themes/tha").body!!, "$.name")).isEqualTo("Th, fixed")
        assertThat(call(HttpMethod.DELETE, "$versions/1.0.0").statusCode).isEqualTo(HttpStatus.CONFLICT)

        // Releasing the lower of two drafts leaves the higher behind it.
        assertThat(call(HttpMethod.POST, versions, """{"version":"2.0.0"}""").statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(call(HttpMethod.POST, "$versions/1.0.1/mark-ready").statusCode).isEqualTo(HttpStatus.OK)
        val lower = call(HttpMethod.POST, "$versions/1.0.1/release", "{}")
        assertThat(lower.statusCode).describedAs(lower.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(lower.body!!, "$.draftsBehind")).containsExactly("2.0.0")
        assertThat(JsonPath.read<String?>(lower.body!!, "$.openedDraft")).isNull()
        assertThat(JsonPath.read<List<String>>(call(HttpMethod.GET, "$versions/2.0.0").body!!, "$.behind[*].version")).containsExactly("1.0.1")

        val refused = call(HttpMethod.POST, "$versions/2.0.0/release", "{}")
        assertThat(refused.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(JsonPath.read<String>(refused.body!!, "$.type")).isEqualTo("https://epistola.app/errors/catalog-version-behind")
        val higher = call(HttpMethod.POST, "$versions/2.0.0/release", """{"acknowledgedBehind":["1.0.1"]}""")
        assertThat(higher.statusCode).describedAs(higher.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(higher.body!!, "$.openedDraft")).isEqualTo("2.0.1")
    }

    @Test
    fun `discarding a draft that alone holds the tenant's default theme is a held problem`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "held-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator {
            val catalogKey = CatalogKey.of(catalogSlug)
            ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = catalogKey, version = "1.0.0").execute()
            CreateCatalogVersion(tenantKey, catalogKey, "2.0.0").execute()
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), CatalogId(catalogKey, TenantId(tenantKey))), "Seasonal", catalogVersion = CatalogVersionRef(tenantKey, catalogKey, "2.0.0")).execute()
            SetTenantDefaultTheme(tenantKey, ThemeKey.of("seasonal"), catalogKey).execute()
        }
        val versions = "${withMediator { CatalogApiPaths.catalog(tenantKey, catalogSlug) }}/versions"

        val refused = restTemplate.exchange("$versions/2.0.0", HttpMethod.DELETE, HttpEntity(null, baseHeaders(apiKey)), String::class.java)

        assertThat(refused.statusCode).describedAs(refused.body).isEqualTo(HttpStatus.CONFLICT)
        assertThat(JsonPath.read<String>(refused.body!!, "$.type")).isEqualTo("https://epistola.app/errors/catalog-version-held")
        assertThat(JsonPath.read<List<String>>(refused.body!!, "$.heldBy")).containsExactly("the tenant's default theme (theme $catalogSlug/seasonal)")
    }

    @Test
    fun `release rejects a subscribed catalog with read only problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        withMediator {
            RegisterCatalog(tenantKey = tenantKey, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenantKey, catalogKey = CatalogKey.of("epistola-demo")).execute()
        }

        // A subscribed catalog has no draft: its versions are the releases it installed.
        val response = restTemplate.exchange(
            "${versionPath(tenantKey, "epistola-demo")}/release",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        assertThat(JsonPath.read<String>(response.body!!, "$.type")).isEqualTo("https://epistola.app/errors/catalog-version-released")
    }

    @Test
    fun `upgrade catalog applies subscribed catalog upgrade request`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        withMediator {
            RegisterCatalog(tenantKey = tenantKey, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenantKey, catalogKey = CatalogKey.of("epistola-demo")).execute()
        }

        val response = restTemplate.exchange(
            "${withMediator { CatalogApiPaths.catalog(tenantKey, "epistola-demo") }}/upgrade",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.newVersion")).isNotBlank()
        assertThat(JsonPath.read<Boolean>(body, "$.aborted")).isFalse
        assertThat(JsonPath.read<List<Any>>(body, "$.installResults")).isNotNull
        assertThat(JsonPath.read<List<Any>>(body, "$.removedResources")).isNotNull
    }

    @Test
    fun `upgrade rejects an authored catalog with not upgradeable problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "upg-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)

        val response = restTemplate.exchange(
            "${withMediator { CatalogApiPaths.catalog(tenantKey, catalogSlug) }}/upgrade",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/catalog-not-upgradeable")
        assertThat(JsonPath.read<String>(body, "$.catalogId")).isEqualTo(catalogSlug)
    }

    private fun versionPath(tenantKey: TenantKey, catalogKey: String, version: String? = null): String = withMediator { CatalogApiPaths.version(tenantKey, catalogKey, version) }

    private fun seedAuthoredCatalog(tenantKey: TenantKey, slug: String) = withMediator {
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of(slug)
        CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Rel $slug").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("tha"), CatalogId(catalogKey, tenantId)), name = "Th").execute()
        // A release takes what is marked ready, so the theme is marked ready to be releasable.
        MarkCatalogReady(tenantKey, catalogKey).execute()
    }

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.14.0 catalog-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("ca-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Catalog API Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "ca-it").execute()
        tenantKey to created.plaintextKey
    }
}
