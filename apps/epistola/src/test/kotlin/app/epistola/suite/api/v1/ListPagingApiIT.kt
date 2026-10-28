// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.testCatalog
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

/**
 * Each REST list pages in the database through its `…Page` query; the order and the cut are tested
 * there (`PageQueriesTest`). This holds the envelope each list endpoint makes of it: a middle page has
 * one row of the size asked, and its page says the number asked, the whole count and the page count;
 * a page past the end is empty and still says the count.
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
class ListPagingApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `every catalog-version list pages in its envelope`() {
        val (tenantKey, key) = seedTenantAndKey()
        val version = withMediator {
            val catalog = testCatalog(TenantId(tenantKey), CATALOG)
            CreateCatalog(tenantKey = tenantKey, id = CATALOG, name = "Paged").execute()
            listOf("a", "b", "c").forEach {
                ImportFont(catalog = catalog, slug = "font-$it", name = "Font $it", kind = "sans").execute()
                CreateCodeList(CodeListId(CodeListKey.of("list-$it"), catalog), "List $it", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("k", "v"))).execute()
                CreateAttributeDefinition(AttributeId(AttributeKey.of("attr-$it"), catalog), "Attr $it").execute()
                CreateTheme(ThemeId(ThemeKey.of("theme-$it"), catalog), "Theme $it").execute()
            }
            val template = TemplateId(TemplateKey.of("letter"), catalog)
            CreateDocumentTemplate(template, "Letter").execute()
            listOf("a", "b").forEach { CreateVariant(VariantId(VariantKey.of("variant-$it"), template), "Variant $it", description = null).execute() }
            CatalogApiPaths.version(tenantKey, CATALOG.value)
        }
        listOf("fonts", "code-lists", "attributes", "themes", "templates/letter/variants").forEach { list ->
            assertPaged("$version/$list", key, total = 3)
        }
    }

    @Test
    fun `catalog versions page in their envelope`() {
        val (tenantKey, key) = seedTenantAndKey()
        val catalogPath = withMediator {
            val catalog = testCatalog(TenantId(tenantKey), CATALOG)
            CreateCatalog(tenantKey = tenantKey, id = CATALOG, name = "Paged").execute()
            CreateTheme(ThemeId(ThemeKey.of("th1"), catalog), "T1").execute()
            listOf("1.0.0", "1.1.0").forEach {
                MarkCatalogReady(catalog).execute()
                ReleaseCatalogDraft(catalog = catalog, version = it).execute()
            }
            CatalogApiPaths.catalog(tenantKey, CATALOG.value)
        }
        // The next version, then both releases.
        assertPaged("$catalogPath/releases", key, total = 3)
    }

    @Test
    fun `tenant-wide lists page in their envelope`() {
        val (tenantKey, key) = seedTenantAndKey()
        val catalogs = withMediator {
            listOf("a", "b").forEach { CreateCatalog(tenantKey = tenantKey, id = CatalogKey.of("more-$it"), name = "More $it").execute() }
            listOf("a", "b", "c").forEach { CreateEnvironment(EnvironmentId(EnvironmentKey.of("env-$it"), TenantId(tenantKey)), "Env $it").execute() }
            ListCatalogs(tenantKey).query().size
        }
        assertThat(catalogs).isGreaterThanOrEqualTo(3)
        assertPaged("/api/tenants/${tenantKey.value}/catalogs", key, total = catalogs)
        assertPaged("/api/tenants/${tenantKey.value}/environments", key, total = 3)
    }

    private fun assertPaged(path: String, apiKey: String, total: Int) {
        val middle = get("$path?page=1&size=1", apiKey)
        assertThat(JsonPath.read<List<Any>>(middle, "$.items")).describedAs("$path page 1").hasSize(1)
        assertThat(JsonPath.read<Int>(middle, "$.page.number")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(middle, "$.page.size")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(middle, "$.page.total")).describedAs("$path total").isEqualTo(total)
        assertThat(JsonPath.read<Int>(middle, "$.page.totalPages")).isEqualTo(total)

        val pastTheEnd = get("$path?page=40&size=2", apiKey)
        assertThat(JsonPath.read<List<Any>>(pastTheEnd, "$.items")).describedAs("$path past the end").isEmpty()
        assertThat(JsonPath.read<Int>(pastTheEnd, "$.page.total")).describedAs("$path total past the end").isEqualTo(total)
    }

    private fun get(path: String, apiKey: String): String {
        val response = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<String>(null, headers(apiKey)), String::class.java)
        assertThat(response.statusCode).describedAs("GET $path: ${response.body}").isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 paging-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("pg-${UUID.randomUUID().toString().take(8)}")
        TestNamespaces.createTenant(tenantKey, "Paging Tenant").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "paging-it").execute().plaintextKey
    }

    private companion object {
        val CATALOG: CatalogKey = CatalogKey.of("paged")
    }
}
