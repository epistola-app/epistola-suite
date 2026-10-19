// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
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
 * Optimistic locking on the REST API: a read carries the resource's ETag, and an update that sends it
 * back in `If-Match` is refused with `resource-modified` (412) when someone changed the resource
 * since. Without `If-Match` an update writes as before.
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
class OptimisticLockingApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a variant's content is written only against the ETag it was read at`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val templateKey = TestIdHelpers.nextTemplateId()
        withMediator { CreateDocumentTemplate(TemplateId(templateKey, CatalogId.default(TenantId(tenantKey))), "Locked").execute().withRequiredDataExample() }
        val content = "${withMediator { CatalogApiPaths.version(tenantKey) }}/templates/${templateKey.value}/variants/${VariantKey.INITIAL.value}/content"

        val read = call(HttpMethod.GET, content, apiKey)
        assertThat(read.statusCode).isEqualTo(HttpStatus.OK)
        val etag = read.headers.eTag
        assertThat(etag).matches("\"[0-9]+\"")
        val body = JsonPath.parse("{}").put("$", "templateModel", JsonPath.read<Any>(read.body!!, "$.templateModel")).jsonString()

        val mine = call(HttpMethod.PUT, content, apiKey, renamed(body, "mine"), ifMatch = etag)
        assertThat(mine.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(mine.headers.eTag).`as`("the new revision").isNotEqualTo(etag)

        val stale = call(HttpMethod.PUT, content, apiKey, renamed(body, "theirs"), ifMatch = etag)
        assertThat(stale.statusCode).isEqualTo(HttpStatus.PRECONDITION_FAILED)
        assertThat(JsonPath.read<String>(stale.body!!, "$.type")).endsWith("/resource-modified")
        assertThat(call(HttpMethod.GET, content, apiKey).body).`as`("the first write stands").contains("mine").doesNotContain("theirs")

        assertThat(call(HttpMethod.PUT, content, apiKey, renamed(body, "fresh"), ifMatch = mine.headers.eTag).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(call(HttpMethod.PUT, content, apiKey, renamed(body, "anyway")).statusCode).`as`("no If-Match: last write wins").isEqualTo(HttpStatus.OK)
        assertThat(call(HttpMethod.PUT, content, apiKey, renamed(body, "bad"), ifMatch = "not-an-etag").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a template's ETag covers its name and data contract`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val templateKey = TestIdHelpers.nextTemplateId()
        withMediator { CreateDocumentTemplate(TemplateId(templateKey, CatalogId.default(TenantId(tenantKey))), "Locked").execute().withRequiredDataExample() }
        val template = "${withMediator { CatalogApiPaths.version(tenantKey) }}/templates/${templateKey.value}"

        val etag = call(HttpMethod.GET, template, apiKey).headers.eTag
        assertThat(call(HttpMethod.PATCH, template, apiKey, """{"name":"Renamed"}""", ifMatch = etag).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(call(HttpMethod.PATCH, template, apiKey, """{"dataModel":{"type":"object"}}""", ifMatch = etag).statusCode)
            .isEqualTo(HttpStatus.PRECONDITION_FAILED)
    }

    /** The request body with a text node saying [text] in the model's root slot: a change the server keeps. */
    private fun renamed(body: String, text: String): String {
        val document = JsonPath.parse(body)
        val slot = document.read<Map<String, Any?>>("$.templateModel.slots").keys.first()
        val paragraph = mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text)))
        val node = mapOf("id" to "t-1", "type" to "text", "slots" to emptyList<String>(), "props" to mapOf("content" to mapOf("type" to "doc", "content" to listOf(paragraph))))
        return document
            .put("$.templateModel.nodes", "t-1", node)
            .set("$.templateModel.slots['$slot'].children", listOf("t-1"))
            .jsonString()
    }

    private fun call(method: HttpMethod, path: String, apiKey: String, body: String? = null, ifMatch: String? = null) = restTemplate.exchange(
        path,
        method,
        HttpEntity(body, baseHeaders(apiKey).apply { if (ifMatch != null) set(HttpHeaders.IF_MATCH, ifMatch) }),
        String::class.java,
    )

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 locking-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("ol-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Locking API Tenant").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "ol-it").execute().plaintextKey
    }
}
