// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
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
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.createApiKey
import app.epistola.suite.testing.default
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.testing.withoutSystemCatalog
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
 * Contract 2.0.0-rc.2: a catalog's templates across its releases (`listCatalogTemplates`), and the
 * `missing` member that says what a `not-found` problem did not find, echoed as `missing=<kind>` in
 * its help link.
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
class CatalogTemplatesAndMissingApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a catalog's templates list every release that has them, and with an environment only what it serves`() {
        val fixture = twoReleases()
        exchange(HttpMethod.PUT, "${deployments(fixture.tenant)}/${fixture.first}", fixture.key)
        val templates = "${catalogPath(fixture.tenant)}/templates"

        val all = exchange(HttpMethod.GET, templates, fixture.key)
        assertThat(all.statusCode).describedAs(all.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(all.body!!, "$.items[*].key")).containsExactly("invoice", "letter")
        assertThat(JsonPath.read<List<String>>(all.body!!, "$.items[0].releases[*].version"))
            .`as`("highest first").containsExactly(fixture.second, fixture.first)
        assertThat(JsonPath.read<List<String>>(all.body!!, "$.items[0].releases[*].status")).containsOnly("released")
        assertThat(JsonPath.read<List<String>>(all.body!!, "$.items[1].releases[*].version"))
            .`as`("added in the second release").containsExactly(fixture.second)
        assertThat(JsonPath.read<String>(all.body!!, "$.items[1].name")).isEqualTo("Letter")
        assertThat(JsonPath.read<Int>(all.body!!, "$.page.total")).isEqualTo(2)

        val paged = exchange(HttpMethod.GET, "$templates?page=1&size=1", fixture.key)
        assertThat(JsonPath.read<List<String>>(paged.body!!, "$.items[*].key")).containsExactly("letter")
        assertThat(JsonPath.read<Int>(paged.body!!, "$.page.total")).isEqualTo(2)

        val searched = exchange(HttpMethod.GET, "$templates?q=lett", fixture.key)
        assertThat(JsonPath.read<List<String>>(searched.body!!, "$.items[*].key")).containsExactly("letter")

        val served = exchange(HttpMethod.GET, "$templates?environmentKey=production", fixture.key)
        assertThat(served.statusCode).describedAs(served.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(served.body!!, "$.items[*].key")).`as`("only what production serves").containsExactly("invoice")
        assertThat(JsonPath.read<List<String>>(served.body!!, "$.items[0].releases[*].version")).containsExactly(fixture.first)

        val nowhere = exchange(HttpMethod.GET, "$templates?environmentKey=nowhere", fixture.key)
        assertThat(nowhere.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<String>(nowhere.body!!, "$.missing.kind")).isEqualTo("environment")
        assertThat(JsonPath.read<String>(nowhere.body!!, "$.missing.environment")).isEqualTo("nowhere")
        assertThat(JsonPath.read<String>(nowhere.body!!, "$.help"))
            .isEqualTo("https://epistola.app/errors/not-found?operation=listCatalogTemplates&missing=environment")
    }

    @Test
    fun `a generation that finds nothing says what is missing, and in which context`() {
        val fixture = twoReleases()
        exchange(HttpMethod.PUT, "${deployments(fixture.tenant)}/${fixture.first}", fixture.key)
        val namespace = withMediator { CatalogApiPaths.namespace(fixture.tenant) }
        val generate = { template: String, range: String?, environment: String? ->
            val fields = listOfNotNull(
                range?.let { "\"versionRange\":\"$it\"" },
                environment?.let { "\"environmentKey\":\"$it\"" },
            ).joinToString("") { "$it," }
            exchange(
                HttpMethod.POST,
                "/api/tenants/${fixture.tenant.value}/documents/generate",
                fixture.key,
                """{"namespace":"$namespace","catalogKey":"default","templateKey":"$template",$fields"data":{}}""",
            )
        }

        val noRelease = generate("invoice", "^9.0.0", "production")
        assertThat(noRelease.statusCode).describedAs(noRelease.body).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<Map<String, Any>>(noRelease.body!!, "$.missing"))
            .isEqualTo(mapOf("kind" to "release", "environment" to "production", "versionRange" to "^9.0.0"))
        assertThat(JsonPath.read<String>(noRelease.body!!, "$.help")).endsWith("?operation=generateDocument&missing=release")

        val unknownRelease = generate("invoice", "9.9.9", null)
        assertThat(JsonPath.read<Map<String, Any>>(unknownRelease.body!!, "$.missing"))
            .isEqualTo(mapOf("kind" to "release", "versionRange" to "9.9.9"))

        val notInRelease = generate("letter", fixture.first, null)
        assertThat(notInRelease.statusCode).describedAs(notInRelease.body).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<Map<String, Any>>(notInRelease.body!!, "$.missing"))
            .`as`("the letter came with the second release")
            .isEqualTo(mapOf("kind" to "template", "release" to fixture.first, "template" to "letter"))
        assertThat(JsonPath.read<String>(notInRelease.body!!, "$.type")).`as`("the type stays exact").isEqualTo("https://epistola.app/errors/not-found")

        val noEnvironment = generate("invoice", null, "nowhere")
        assertThat(JsonPath.read<String>(noEnvironment.body!!, "$.missing.kind")).isEqualTo("environment")
    }

    private data class Fixture(val tenant: TenantKey, val key: String, val first: String, val second: String)

    /** The default catalog: a first release with template `invoice`, a second adding `letter`; an environment `production`. */
    private fun twoReleases(): Fixture = withMediator {
        val tenant = TenantKey.of("ctl-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenant, "Catalog Templates Tenant")
        val catalog = CatalogId.default(TenantId(tenant))
        withoutSystemCatalog(catalog)
        CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), "Production").execute()
        val first = mediator.publishAndRelease(template(catalog, "invoice", "Invoice"))
        val second = mediator.publishAndRelease(template(catalog, "letter", "Letter"))
        Fixture(tenant, createApiKey(tenant, "ctl-it").plaintextKey, first, second)
    }

    private fun template(catalog: CatalogId, key: String, name: String): VariantId {
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of(key), catalog))
        CreateDocumentTemplate(variant.templateId, name, catalogVersion = null).execute().withRequiredDataExample()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = name), catalogVersion = null).execute()
        return variant
    }

    private fun catalogPath(tenant: TenantKey): String = withMediator { CatalogApiPaths.catalog(tenant) }

    private fun deployments(tenant: TenantKey): String {
        val namespace = withMediator { CatalogApiPaths.namespace(tenant) }
        return "/api/tenants/${tenant.value}/environments/production/deployments/~/$namespace/default"
    }

    private fun exchange(method: HttpMethod, path: String, key: String, body: String? = null) = restTemplate.exchange(path, method, HttpEntity(body, headers(key)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 catalog-templates-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }
}
