// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
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
    fun `releases list newest first with the environments serving them, and one reads with its dependencies`() {
        val (tenant, key) = tenantWithTwoReleases()
        exchange(HttpMethod.PUT, "/api/tenants/${tenant.value}/environments/production/deployments/default", key, """{"releaseVersion":"1.0.0"}""")

        val list = exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/catalogs/default/releases", key)
        assertThat(list.statusCode).describedAs(list.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(list.body!!, "$.items[*].releaseVersion")).containsExactly("1.0.1", "1.0.0")
        assertThat(JsonPath.read<List<String>>(list.body!!, "$.items[1].deployedTo")).containsExactly("production")
        assertThat(JsonPath.read<Int>(list.body!!, "$.page.totalElements")).isEqualTo(2)

        val paged = exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/catalogs/default/releases?page=1&size=1", key)
        assertThat(JsonPath.read<List<String>>(paged.body!!, "$.items[*].releaseVersion")).`as`("paged in the database").containsExactly("1.0.0")

        val one = exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/catalogs/default/releases/1.0.1", key)
        assertThat(one.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<Boolean>(one.body!!, "$.contentRetained")).isTrue()
        assertThat(JsonPath.read<List<Any>>(one.body!!, "$.dependencies")).isNotNull()

        val missing = exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/catalogs/default/releases/9.9.9", key)
        assertThat(missing.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<String>(missing.body!!, "$.type")).isEqualTo("https://epistola.app/errors/release-not-found")
    }

    @Test
    fun `deploying, the deployments listing, the history and undeploying`() {
        val (tenant, key) = tenantWithTwoReleases()
        val env = "/api/tenants/${tenant.value}/environments/production"

        assertThat(exchange(HttpMethod.PUT, "$env/deployments/default", key, """{"releaseVersion":"1.0.0"}""").statusCode).isEqualTo(HttpStatus.OK)
        val moved = exchange(HttpMethod.PUT, "$env/deployments/default", key, """{"releaseVersion":"1.0.1"}""")
        assertThat(JsonPath.read<String>(moved.body!!, "$.releaseVersion")).isEqualTo("1.0.1")

        val serves = exchange(HttpMethod.GET, "$env/deployments", key)
        assertThat(JsonPath.read<List<String>>(serves.body!!, "$.items[*].releaseVersion")).containsExactly("1.0.1")

        assertThat(exchange(HttpMethod.DELETE, "$env/deployments/default", key).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        val again = exchange(HttpMethod.DELETE, "$env/deployments/default", key)
        assertThat(again.statusCode).`as`("nothing left to undeploy").isEqualTo(HttpStatus.NOT_FOUND)

        val history = exchange(HttpMethod.GET, "$env/deployment-history", key)
        assertThat(JsonPath.read<List<String>>(history.body!!, "$.items[*].action")).containsExactly("undeployed", "deployed", "deployed")
        assertThat(JsonPath.read<String>(history.body!!, "$.items[1].previousReleaseVersion")).isEqualTo("1.0.0")
        assertThat(JsonPath.read<Int>(history.body!!, "$.page.totalElements")).isEqualTo(3)

        val notDeployable = exchange(HttpMethod.PUT, "$env/deployments/default", key, """{"releaseVersion":"9.9.9"}""")
        assertThat(notDeployable.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `a generation request naming a release renders it, and naming an unknown one is refused`() {
        val (tenant, key) = tenantWithTwoReleases()
        val body = { release: String -> """{"catalogId":"default","templateId":"invoice","variantId":"${VariantKey.INITIAL.value}","releaseVersion":"$release","data":{}}""" }

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
    fun `the catalog's changes show an edit as modified, and marking the catalog ready makes it ready`() {
        val (tenant, key) = tenantWithTwoReleases()
        withMediator {
            UpdateDraft(variant(tenant), TestTemplateBuilder.buildMinimal(name = "Invoice edited")).execute()
        }

        val changes = exchange(HttpMethod.GET, "/api/tenants/${tenant.value}/catalogs/default/changes", key)
        assertThat(changes.statusCode).describedAs(changes.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(changes.body!!, "$.latestRelease")).isEqualTo("1.0.1")
        assertThat(JsonPath.read<List<String>>(changes.body!!, "$.changes[?(@.slug == 'invoice')].change")).containsExactly("modified")

        val ready = exchange(HttpMethod.POST, "/api/tenants/${tenant.value}/catalogs/default/mark-ready", key)
        assertThat(ready.statusCode).describedAs(ready.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(ready.body!!, "$.changes[?(@.slug == 'invoice')].change")).containsExactly("ready")
        assertThat(JsonPath.read<Boolean>(ready.body!!, "$.releasable")).isTrue()
    }

    /** The default catalog with releases 1.0.0 and 1.0.1 of a template `invoice`, and an environment `production`. */
    private fun tenantWithTwoReleases(): Pair<TenantKey, String> = withMediator {
        val tenant = TenantKey.of("rel-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenant, name = "Release Tenant").execute()
        CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), "Production").execute()
        val variant = variant(tenant)
        CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
        UpdateDraft(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
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
