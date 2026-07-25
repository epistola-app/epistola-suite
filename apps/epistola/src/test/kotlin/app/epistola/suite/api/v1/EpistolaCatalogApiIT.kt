// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.CreateTenant
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

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/demo/catalog.json"

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
    fun `release catalog cuts authored catalog version`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "rel-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/$catalogSlug/release",
            HttpMethod.POST,
            HttpEntity("""{"releaseVersion":"1.0.0","notes":"first cut"}""", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.version")).isEqualTo("1.0.0")
        assertThat(JsonPath.read<String>(body, "$.fingerprint")).isNotBlank()
        assertThat(JsonPath.read<Boolean>(body, "$.unchangedContent")).isFalse
    }

    @Test
    fun `upgrade catalog applies subscribed catalog upgrade request`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        withMediator {
            RegisterCatalog(tenantKey = tenantKey, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenantKey, catalogKey = CatalogKey.of("epistola-demo")).execute()
        }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/epistola-demo/upgrade",
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

    private fun seedAuthoredCatalog(tenantKey: TenantKey, slug: String) = withMediator {
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of(slug)
        CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Rel $slug").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("tha"), CatalogId(catalogKey, tenantId)), name = "Th").execute()
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
