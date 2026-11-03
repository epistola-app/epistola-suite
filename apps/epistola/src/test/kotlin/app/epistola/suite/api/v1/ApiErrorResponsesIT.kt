// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import java.net.Socket
import java.util.UUID

/**
 * Requests a client gets wrong answer a problem of the contract's 4xx type, never a 500 or Spring
 * Boot's default error body (`timestamp`, `trace`): the failures fuzzing an upgraded 2.0 instance
 * found. Requests the HTTP client would refuse to send go over a raw socket, as a fuzzer sends them.
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
class ApiErrorResponsesIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a body failing the contract's constraints answers a validation problem naming the field`() {
        val (tenantKey, key) = seedTenantAndKey()

        val response = call(HttpMethod.PATCH, "/api/tenants/${tenantKey.value}", key, """{"name":""}""")

        assertProblem(response, HttpStatus.BAD_REQUEST, "validation-error")
        assertThat(JsonPath.read<List<String>>(response.body!!, "$.errors[*].field")).containsExactly("name")
    }

    @Test
    fun `a missing required query parameter answers a problem`() {
        val (tenantKey, key) = seedTenantAndKey()
        val draft = withMediator { CatalogApiPaths.version(tenantKey) }

        val response = call(HttpMethod.GET, "$draft/merge-preview", key)

        assertProblem(response, HttpStatus.BAD_REQUEST, "validation-error")
        assertThat(JsonPath.read<List<String>>(response.body!!, "$.errors[*].field")).containsExactly("version")
    }

    @Test
    fun `a page size above the contract's maximum answers 400`() {
        val (tenantKey, key) = seedTenantAndKey()
        val draft = withMediator { CatalogApiPaths.version(tenantKey) }

        for (path in listOf("/api/tenants?size=101", "/api/tenants/${tenantKey.value}/environments?size=101", "$draft/templates?size=101")) {
            val response = call(HttpMethod.GET, path, key)
            assertProblem(response, HttpStatus.BAD_REQUEST, "validation-error")
            assertThat(JsonPath.read<List<String>>(response.body!!, "$.errors[*].field")).`as`(path).containsExactly("size")
        }
    }

    @Test
    fun `validating data for a template the release lacks answers 404`() {
        val (tenantKey, key) = seedTenantAndKey()
        val draft = withMediator { CatalogApiPaths.version(tenantKey) }
        val catalog = withMediator { CatalogApiPaths.catalog(tenantKey) }
        assertThat(call(HttpMethod.POST, "$draft/templates", key, """{"key": "kept", "name": "Kept"}""").statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(call(HttpMethod.POST, "$draft/templates/kept/mark-ready", key).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(call(HttpMethod.POST, "$draft/release", key, "{}").statusCode).isEqualTo(HttpStatus.OK)

        val response = call(HttpMethod.POST, "$catalog/templates/absent/validate", key, """{"data": {}}""")

        assertThat(response.statusCode).`as`(response.body).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(JsonPath.read<String>(response.body!!, "$.templateId")).isEqualTo("absent")
    }

    @Test
    fun `a query string the container cannot parse answers 400`() {
        val (_, key) = seedTenantAndKey()

        for (query in listOf("=null", "=", "=false")) {
            val response = raw("GET /api/tenants?$query HTTP/1.1", key)
            assertThat(response.status).`as`("?$query: ${response.body}").isEqualTo(400)
            assertThat(response.body).contains("https://epistola.app/errors/bad-request")
        }
    }

    @Test
    fun `a header the firewall refuses answers 400`() {
        val (tenantKey, key) = seedTenantAndKey()
        val theme = withMediator { CatalogApiPaths.version(tenantKey) } + "/themes/brand"
        assertThat(call(HttpMethod.POST, theme.substringBeforeLast('/'), key, """{"key": "brand", "name": "Brand"}""").statusCode).isEqualTo(HttpStatus.CREATED)

        // A value with a C1 control character (NEL, 0x85): HTTP allows the byte, Spring Security's
        // firewall does not, and refuses it when the controller reads the header.
        val response = raw("PATCH $theme HTTP/1.1", key, """{"name":"Renamed"}""", "If-Match: \"1\u0085\"")

        assertThat(response.status).`as`(response.body).isEqualTo(400)
        assertThat(response.contentType).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        assertThat(response.body).contains("https://epistola.app/errors/bad-request")
    }

    @Test
    fun `a path the firewall refuses answers a 400 problem`() {
        val (tenantKey, key) = seedTenantAndKey()

        val response = raw("GET /api/tenants/${tenantKey.value};jsessionid=x HTTP/1.1", key)

        assertThat(response.status).`as`(response.body).isEqualTo(400)
        assertThat(response.contentType).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        assertThat(response.body).contains("https://epistola.app/errors/bad-request")
    }

    @Test
    fun `a NUL character in a string answers 400`() {
        val (tenantKey, key) = seedTenantAndKey()

        // The JSON escape, which the body's parser turns into the character.
        val response = call(HttpMethod.PATCH, "/api/tenants/${tenantKey.value}", key, """{"name":"Re\u0000named"}""")

        assertProblem(response, HttpStatus.BAD_REQUEST, "bad-request")
    }

    @Test
    fun `creating an environment under a key that is taken answers 409`() {
        val (tenantKey, key) = seedTenantAndKey()
        val body = """{"key": "staging", "name": "Staging"}"""
        assertThat(call(HttpMethod.POST, "/api/tenants/${tenantKey.value}/environments", key, body).statusCode).isEqualTo(HttpStatus.CREATED)

        val response = call(HttpMethod.POST, "/api/tenants/${tenantKey.value}/environments", key, body)

        assertProblem(response, HttpStatus.CONFLICT, "conflict")
    }

    private fun assertProblem(response: ResponseEntity<String>, status: HttpStatus, slug: String) {
        assertThat(response.statusCode).`as`(response.body).isEqualTo(status)
        assertThat(response.headers.contentType?.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).`as`("content type ${response.headers.contentType}").isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/$slug")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(status.value())
        assertThat(body).doesNotContain("\"trace\"").doesNotContain("\"timestamp\"")
    }

    private fun call(method: HttpMethod, path: String, apiKey: String, body: String? = null): ResponseEntity<String> = restTemplate.exchange(path, method, HttpEntity(body, headers(apiKey)), String::class.java)

    private class RawResponse(val status: Int, val contentType: String, val body: String)

    /** Sends [requestLine] as is, with the usual API headers and [extraHeaders], over a plain socket. */
    private fun raw(requestLine: String, apiKey: String, body: String? = null, vararg extraHeaders: String): RawResponse {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("$requestLine\r\n")
            append("Host: localhost:$port\r\n")
            append("Accept: application/vnd.epistola.v2+json\r\n")
            append("User-Agent: epistola-contract/2.0.0 api-error-it\r\n")
            append("X-EP-Node-Id: test-node-${UUID.randomUUID()}\r\n")
            append("Authorization: ApiKey $apiKey\r\n")
            if (bytes != null) {
                append("Content-Type: application/vnd.epistola.v2+json\r\n")
                append("Content-Length: ${bytes.size}\r\n")
            }
            extraHeaders.forEach { append("$it\r\n") }
            append("Connection: close\r\n\r\n")
        }
        val text = Socket("localhost", port).use { socket ->
            socket.soTimeout = 30_000
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                bytes?.let { write(it) }
                flush()
            }
            socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
        }
        val status = text.substringAfter(' ').substringBefore(' ').toInt()
        val headers = text.substringBefore("\r\n\r\n").lines()
        val contentType = headers.firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
        return RawResponse(status, contentType, text.substringAfter("\r\n\r\n"))
    }

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 api-error-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("err-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenantKey, "Error Responses Tenant")
        val created = CreateApiKey(tenantId = tenantKey, name = "api-error-it", roles = TenantRole.entries.toSet()).execute()
        tenantKey to created.plaintextKey
    }
}
