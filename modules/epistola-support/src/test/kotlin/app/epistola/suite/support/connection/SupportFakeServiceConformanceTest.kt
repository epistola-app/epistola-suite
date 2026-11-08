// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.testing.FakeSupportServer
import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * The suite is tested against a stand-in support service ([FakeSupportServer]). Its answers, and the
 * registration request the suite sends, are checked here against `docs/support-api.yaml`, the copy of
 * the support service's OpenAPI file, which the service checks its own answers against: so the
 * stand-in cannot drift from the real service unnoticed.
 */
@Tag("unit")
class SupportFakeServiceConformanceTest {
    private val validator =
        OpenApiInteractionValidator
            .createForSpecificationUrl(Path.of("../../docs/support-api.yaml").toAbsolutePath().normalize().toUri().toString())
            .withBasePathOverride("/")
            .build()
    private val http = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()
    private val problems = mutableListOf<String>()

    private fun call(support: FakeSupportServer, method: String, path: String, token: String? = null, json: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(support.baseUrl + path))
            .method(method, json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        token?.let { builder.header("Authorization", "Bearer $it") }
        json?.let { builder.header("Content-Type", "application/json") }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val request = SimpleRequest.Builder(Request.Method.valueOf(method), path).apply {
            token?.let { withAuthorization("Bearer $it") }
            json?.let { withContentType("application/json").withBody(it) }
        }.build()
        val answer = SimpleResponse.Builder(response.statusCode()).apply {
            response.headers().firstValue("Content-Type").ifPresent { withContentType(it) }
            if (response.body().isNotEmpty()) withBody(response.body())
        }.build()
        validator.validate(request, answer).messages.filter { it.level.name == "ERROR" }.forEach { problems += "$method $path → ${response.statusCode()}: ${it.message}" }
        return response
    }

    @Test
    fun `the stand-in support service answers as docs support-api yaml says, to the registration the suite sends`() {
        FakeSupportServer().use { support ->
            support.start()
            val installation = InstallationProperties(companyName = "Gemeente Test", adminEmail = "beheer@test.example", environment = "production")
            val registration = supportRegistrationRequest(SupportClientKeys.generate(), UUID.randomUUID().toString(), installation, supportContact(null, installation), "node-1", "2.0.0")
            call(support, "POST", "/oauth2/register", json = mapper.writeValueAsString(registration))
            val clientId = support.latestRegistration.clientId
            // A token as the token endpoint (RFC 7523, not in the file) would issue it.
            support.issuedTokens["conformance-token"] = clientId to emptySet()

            call(support, "GET", "/api/v1/connection", "conformance-token")
            support.activate(scopes = setOf("requests", "mirror", "usage"))
            support.issuedTokens["conformance-token"] = clientId to setOf("requests", "mirror", "usage")
            call(support, "GET", "/api/v1/connection", "conformance-token")
            call(
                support,
                "PUT",
                "/api/v1/usage/hours/2026-11-06T09:00:00Z",
                "conformance-token",
                json = """{"tenants":[{"tenant":"t-0123456789abcdef","generated":3,"previews":1,"generatedTotal":30,"previewsTotal":10}]}""",
            )
            support.pause(setOf("mirror"), "Maintenance \"with quotes\"", until = Instant.parse("2030-01-01T00:00:00Z"))
            call(support, "GET", "/api/v1/connection", "conformance-token")
            call(support, "DELETE", "/api/v1/connection", "conformance-token")
            call(support, "GET", "/api/v1/connection", "conformance-token")

            call(support, "POST", "/oauth2/register", json = mapper.writeValueAsString(supportRegistrationRequest(SupportClientKeys.generate(), UUID.randomUUID().toString(), installation, supportContact(null, installation), "node-1", "2.0.0")))
            support.decline()
            support.issuedTokens["declined-token"] = support.latestRegistration.clientId to emptySet()
            call(support, "GET", "/api/v1/connection", "declined-token")
        }
        assertThat(problems).`as`("answers of the stand-in that do not match docs/support-api.yaml").isEmpty()
    }
}
