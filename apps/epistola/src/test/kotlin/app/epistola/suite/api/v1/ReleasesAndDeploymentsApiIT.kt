// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
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
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
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
 * The contract 2.0 operations backed by catalog releases: reading releases, the working copy's
 * changes, deployments with their history, and generating from a named release.
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
class ReleasesAndDeploymentsApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `released versions list newest first with the environments serving them, and one reads by version`() {
        val (tenant, key) = tenantWithTwoReleases()
        val catalog = catalogPath(tenant)
        exchange(HttpMethod.PUT, "${deployments(tenant)}/1.0.0", key)

        val list = exchange(HttpMethod.GET, "$catalog/versions?status=released", key)
        assertThat(list.statusCode).describedAs(list.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(list.body!!, "$.items[*].version")).containsExactly("1.0.1", "1.0.0")
        assertThat(JsonPath.read<List<String>>(list.body!!, "$.items[1].deployedTo")).containsExactly("production")
        assertThat(JsonPath.read<Int>(list.body!!, "$.page.totalElements")).isEqualTo(2)

        val all = exchange(HttpMethod.GET, "$catalog/versions", key)
        assertThat(JsonPath.read<List<String?>>(all.body!!, "$.items[*].version")).`as`("the next version, numbered when released, then the releases").containsExactly(null, "1.0.1", "1.0.0")
        assertThat(JsonPath.read<String>(all.body!!, "$.items[0].status")).isEqualTo("draft")

        val paged = exchange(HttpMethod.GET, "$catalog/versions?status=released&page=1&size=1", key)
        assertThat(JsonPath.read<List<String>>(paged.body!!, "$.items[*].version")).containsExactly("1.0.0")

        val one = exchange(HttpMethod.GET, "$catalog/versions/1.0.1", key)
        assertThat(one.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(one.body!!, "$.status")).isEqualTo("released")
        assertThat(JsonPath.read<Boolean>(one.body!!, "$.contentRetained")).isTrue()
        assertThat(JsonPath.read<List<Any>>(one.body!!, "$.dependencies")).isEmpty()

        val missing = exchange(HttpMethod.GET, "$catalog/versions/9.9.9", key)
        assertThat(missing.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<String>(missing.body!!, "$.type")).isEqualTo("https://epistola.app/errors/catalog-version-not-found")
    }

    @Test
    fun `deploying adds a release beside the others, the listing shows both, and undeploying removes one`() {
        val (tenant, key) = tenantWithTwoReleases()
        val env = "/api/tenants/${tenant.value}/environments/production"
        val deployments = deployments(tenant)

        assertThat(exchange(HttpMethod.PUT, "$deployments/1.0.0", key).statusCode).isEqualTo(HttpStatus.OK)
        val added = exchange(HttpMethod.PUT, "$deployments/1.0.1", key)
        assertThat(added.statusCode).describedAs(added.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(added.body!!, "$.releaseVersion")).isEqualTo("1.0.1")

        val serves = exchange(HttpMethod.GET, "$env/deployments", key)
        assertThat(JsonPath.read<List<String>>(serves.body!!, "$.items[*].releaseVersion")).`as`("both served").containsExactly("1.0.1", "1.0.0")

        assertThat(exchange(HttpMethod.DELETE, "$deployments/1.0.1", key).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        val after = exchange(HttpMethod.GET, "$env/deployments", key)
        assertThat(JsonPath.read<List<String>>(after.body!!, "$.items[*].releaseVersion")).`as`("the other stays").containsExactly("1.0.0")
        val again = exchange(HttpMethod.DELETE, "$deployments/1.0.1", key)
        assertThat(again.statusCode).`as`("no longer served").isEqualTo(HttpStatus.NOT_FOUND)

        val history = exchange(HttpMethod.GET, "$env/deployment-history", key)
        assertThat(JsonPath.read<List<String>>(history.body!!, "$.items[*].action")).containsExactly("undeployed", "deployed", "deployed")
        assertThat(JsonPath.read<List<String>>(history.body!!, "$.items[*].releaseVersion")).containsExactly("1.0.1", "1.0.1", "1.0.0")
        assertThat(JsonPath.read<Int>(history.body!!, "$.page.totalElements")).isEqualTo(3)

        val notDeployable = exchange(HttpMethod.PUT, "$deployments/9.9.9", key)
        assertThat(notDeployable.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `generation with an environment and a version selector renders the highest served release satisfying it`() {
        val (tenant, key) = tenantWithTwoReleases()
        exchange(HttpMethod.PUT, "${deployments(tenant)}/1.0.0", key)
        exchange(HttpMethod.PUT, "${deployments(tenant)}/1.0.1", key)
        val namespace = withMediator { CatalogApiPaths.namespace(tenant) }
        val body = { version: String? ->
            val selector = version?.let { "\"version\":\"$it\"," }.orEmpty()
            """{"namespace":"$namespace","catalogKey":"default","templateKey":"invoice","variantKey":"${VariantKey.INITIAL.value}",$selector"environmentKey":"production","data":{}}"""
        }
        val rendered = { version: String? ->
            val accepted = exchange(HttpMethod.POST, "/api/tenants/${tenant.value}/documents/generate", key, body(version))
            assertThat(accepted.statusCode).describedAs(accepted.body).isEqualTo(HttpStatus.ACCEPTED)
            val job = exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/documents/jobs/${JsonPath.read<String>(accepted.body!!, "$.requestId")}", key)
            JsonPath.read<String>(job.body!!, "$.items[0].releaseVersion")
        }

        assertThat(rendered(null)).isEqualTo("1.0.1")
        assertThat(rendered("<1.0.1")).isEqualTo("1.0.0")

        val none = exchange(HttpMethod.POST, "/api/tenants/${tenant.value}/documents/generate", key, body("^2.0.0"))
        assertThat(none.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<String>(none.body!!, "$.type")).isEqualTo("https://epistola.app/errors/no-release-deployed")
    }

    @Test
    fun `a release whose dependency the environment does not serve is deployed, using it implicitly, and deploys it too on request`() {
        val (tenant, key) = tenantWithTwoReleases()
        val version = withMediator {
            val shared = CatalogKey.of("shared")
            CreateCatalog(tenantKey = tenant, id = shared, name = "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant))), "Brand").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogVersion(tenant, shared, "1.0.0").execute()
            val target = GetCatalog(tenant, shared).query()!!
            UpdateCatalogDependencies(tenant, CatalogKey.DEFAULT, listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", "*"))).execute()
            mediator.releaseNext(CatalogId.default(TenantId(tenant)))
        }

        val env = "/api/tenants/${tenant.value}/environments/production"
        val deployed = exchange(HttpMethod.PUT, "${deployments(tenant)}/$version", key)
        assertThat(deployed.statusCode).describedAs(deployed.body).`as`("what it depends on is used implicitly").isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(exchange(HttpMethod.GET, "$env/deployments", key).body!!, "$.items[*].catalogKey"))
            .`as`("only what was deployed").containsExactly("default")

        val along = exchange(HttpMethod.PUT, "${deployments(tenant)}/$version?includeDependencies=true", key)
        assertThat(along.statusCode).describedAs(along.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(along.body!!, "$.releaseVersion")).isEqualTo(version)
        val served = exchange(HttpMethod.GET, "$env/deployments", key)
        assertThat(JsonPath.read<List<String>>(served.body!!, "$.items[*].catalogKey")).`as`("deployed explicitly too, on request")
            .containsExactlyInAnyOrder("default", "shared")
    }

    @Test
    fun `a generation request naming a release renders it, and naming an unknown one is refused`() {
        val (tenant, key) = tenantWithTwoReleases()
        val namespace = withMediator { CatalogApiPaths.namespace(tenant) }
        val body = { release: String ->
            """{"namespace":"$namespace","catalogKey":"default","templateKey":"invoice","variantKey":"${VariantKey.INITIAL.value}","version":"$release","data":{}}"""
        }

        val accepted = exchange(HttpMethod.POST, "/api/tenants/${tenant.value}/documents/generate", key, body("1.0.0"))
        assertThat(accepted.statusCode).describedAs(accepted.body).isEqualTo(HttpStatus.ACCEPTED)
        val requestId = JsonPath.read<String>(accepted.body!!, "$.requestId")
        val job = exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/documents/jobs/$requestId", key)
        assertThat(JsonPath.read<String>(job.body!!, "$.items[0].releaseVersion")).`as`("the named release, not the latest").isEqualTo("1.0.0")

        val unknown = exchange(HttpMethod.POST, "/api/tenants/${tenant.value}/documents/generate", key, body("9.9.9"))
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<String>(unknown.body!!, "$.type")).isEqualTo("https://epistola.app/errors/release-not-found")
    }

    @Test
    fun `the draft's changes show an edit as modified, and marking the draft ready makes it ready`() {
        val (tenant, key) = tenantWithTwoReleases()
        withMediator {
            UpdateVariantContent(variant(tenant), TestTemplateBuilder.buildMinimal(name = "Invoice edited")).execute()
        }
        val draft = withMediator { CatalogApiPaths.version(tenant) }

        val changes = exchange(HttpMethod.GET, "$draft/changes", key)
        assertThat(changes.statusCode).describedAs(changes.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(changes.body!!, "$.base")).isEqualTo("1.0.1")
        assertThat(JsonPath.read<List<String>>(changes.body!!, "$.changes[?(@.key == 'invoice')].change")).containsExactly("modified")

        val ready = exchange(HttpMethod.POST, "$draft/mark-ready", key)
        assertThat(ready.statusCode).describedAs(ready.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(ready.body!!, "$.changes[?(@.key == 'invoice')].change")).containsExactly("ready")
        assertThat(JsonPath.read<Boolean>(ready.body!!, "$.releasable")).isTrue()
    }

    private fun catalogPath(tenant: TenantKey): String = withMediator { CatalogApiPaths.catalog(tenant) }

    /** `…/environments/production/deployments/~/{namespace}/default`: append a release version. */
    private fun deployments(tenant: TenantKey): String {
        val namespace = withMediator { CatalogApiPaths.namespace(tenant) }
        return "/api/tenants/${tenant.value}/environments/production/deployments/~/$namespace/default"
    }

    /** The default catalog with releases 1.0.0 and 1.0.1 of a template `invoice`, and an environment `production`. */
    private fun tenantWithTwoReleases(): Pair<TenantKey, String> = withMediator {
        val tenant = TenantKey.of("rel-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenant, name = "Release Tenant").execute()
        CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), "Production").execute()
        val variant = variant(tenant)
        CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
        mediator.publishAndRelease(variant)
        mediator.releaseNext(variant.templateId.catalogId)
        tenant to CreateApiKey(tenantId = tenant, name = "rel-it").execute().plaintextKey
    }

    private fun variant(tenant: TenantKey) = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), CatalogId.default(TenantId(tenant))))

    private fun exchange(method: HttpMethod, path: String, key: String, body: String? = null) = restTemplate.exchange(path, method, HttpEntity(body, headers(key)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 release-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }
}
