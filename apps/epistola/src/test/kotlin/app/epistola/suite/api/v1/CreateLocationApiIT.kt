// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
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
 * `Location` on every create (contract): a 201 carries the address of what it made, which reads it
 * back. And a listing of documents by template names the template's catalog, since a template key is
 * unique only within one.
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
class CreateLocationApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `every create answers with the address of what it created`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val version = withMediator { CatalogApiPaths.version(tenantKey) }
        val creates = listOf(
            "/api/tenants/${tenantKey.value}/environments" to """{"key":"staging","name":"Staging"}""",
            "$version/themes" to """{"key":"brand","name":"Brand"}""",
            "$version/templates" to """{"key":"invoice","name":"Invoice"}""",
            "$version/templates/invoice/variants" to """{"key":"english","title":"English"}""",
            "$version/stencils" to """{"key":"footer","name":"Footer"}""",
            "$version/code-lists" to """{"key":"colours","displayName":"Colours","sourceType":"inline","entries":[{"code":"red","label":"Red"}]}""",
        )
        for ((collection, request) in creates) {
            val created = call(HttpMethod.POST, collection, apiKey, request)
            assertThat(created.statusCode).describedAs("$collection: ${created.body}").isEqualTo(HttpStatus.CREATED)
            val location = requireNotNull(created.headers.location) { "$collection gave no Location" }
            assertThat(location.path).isEqualTo("$collection/${JsonPath.read<String>(created.body!!, "$.key")}")
            assertThat(call(HttpMethod.GET, location.path, apiKey).statusCode).`as`("$location reads it back").isEqualTo(HttpStatus.OK)
        }
    }

    @Test
    fun `documents are listed by template only within its catalog`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val namespace = withMediator { CatalogApiPaths.namespace(tenantKey) }
        val documents = "/api/tenants/${tenantKey.value}/documents"

        val withoutCatalog = call(HttpMethod.GET, "$documents?templateKey=invoice", apiKey)
        assertThat(withoutCatalog.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(withoutCatalog.body).contains("namespace and catalogKey")

        val inCatalog = call(HttpMethod.GET, "$documents?templateKey=invoice&namespace=$namespace&catalogKey=default", apiKey)
        assertThat(inCatalog.statusCode).describedAs(inCatalog.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<Int>(inCatalog.body!!, "$.page.total")).isZero
    }

    private fun call(method: HttpMethod, path: String, apiKey: String, body: String? = null, ifMatch: String? = null) = restTemplate.exchange(
        path,
        method,
        HttpEntity(body, baseHeaders(apiKey).apply { if (ifMatch != null) set(HttpHeaders.IF_MATCH, ifMatch) }),
        String::class.java,
    )

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 locking-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("ol-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Locking API Tenant").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "ol-it").execute().plaintextKey
    }
}
