// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.testing.FakeSupportServer
import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * The partner spec (`docs/support-partner-api.yaml`, ADR 0053 step 3) is the support protocol as a
 * partner implements it: every operation in it is the Epistola support service's own
 * (`docs/support-api.yaml`), so one connector serves both and the published file cannot drift from
 * what the suite speaks. And a partner's answers to what the suite asks look as it says.
 */
@Tag("unit")
class SupportPartnerSpecTest {
    private val partnerFile = Path.of("../../docs/support-partner-api.yaml").toAbsolutePath().normalize()
    private val serviceFile = Path.of("../../docs/support-api.yaml").toAbsolutePath().normalize()

    /** A YAML file as maps and lists, `$ref`s as written: the operations are compared as the files state them. */
    @Suppress("UNCHECKED_CAST")
    private fun yaml(file: Path): Map<String, Any?> = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(file.toFile().readText())

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `every operation a partner implements is the support service's own`() {
        val partner = yaml(partnerFile)["paths"] as Map<String, Map<String, Any?>>
        val service = yaml(serviceFile)["paths"] as Map<String, Map<String, Any?>>
        val shared = partner.keys - "/.well-known/epistola.json"

        assertThat(shared).isNotEmpty().allMatch { it in service.keys }
        for (path in shared) {
            for ((method, operation) in partner.getValue(path)) {
                // The same operation, its parameters and answers named by the same components; the partner
                // file may relax a component (the partner's own ids are any string).
                assertThat(operation).`as`("$method $path").isEqualTo(service.getValue(path)[method])
            }
        }
        assertThat(partner.getValue("/api/v1/connection").keys).`as`("a partner is not disconnected through the protocol").containsExactly("get")
    }

    @Test
    fun `a partner's answers to the connection check and a usage report are as the partner spec says`() {
        val validator = OpenApiInteractionValidator.createForSpecificationUrl(partnerFile.toUri().toString()).withBasePathOverride("/").build()
        val problems = mutableListOf<String>()
        FakeSupportServer().use { partner ->
            partner.start()
            partner.issuePartnerToken("partner-spec-token", setOf("requests", "usage"))
            val http = HttpClient.newHttpClient()
            fun call(method: String, path: String, json: String? = null) {
                val builder = HttpRequest.newBuilder(URI.create(partner.baseUrl + path)).header("Authorization", "Bearer partner-spec-token")
                    .method(method, json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                json?.let { builder.header("Content-Type", "application/json") }
                val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                val request = SimpleRequest.Builder(Request.Method.valueOf(method), path).withAuthorization("Bearer partner-spec-token").apply {
                    json?.let { withContentType("application/json").withBody(it) }
                }.build()
                val answer = SimpleResponse.Builder(response.statusCode()).apply {
                    response.headers().firstValue("Content-Type").ifPresent { withContentType(it) }
                    if (response.body().isNotEmpty()) withBody(response.body())
                }.build()
                validator.validate(request, answer).messages.filter { it.level.name == "ERROR" }.forEach { problems += "$method $path: ${it.message}" }
            }
            call("GET", "/api/v1/connection")
            call("PUT", "/api/v1/usage/hours/2026-11-09T09:00:00Z", """{"tenants":[{"tenant":"t-0123456789abcdef","generated":3,"previews":1,"generatedTotal":30,"previewsTotal":10}]}""")
        }
        assertThat(problems).isEmpty()
    }
}
