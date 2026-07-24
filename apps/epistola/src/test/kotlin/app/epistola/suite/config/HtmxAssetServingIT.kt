// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.http.HttpStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream

/**
 * Verifies HTMX is self-hosted (no CDN) and served content-hashed, long-cached
 * and compressible. The CSP `script-src` change (dropping `unpkg.com`) cannot be
 * asserted here because integration tests run under the `test` profile, whose
 * security chain omits the CSP header; the "no unpkg in rendered HTML" assertion
 * below is the regression guard for self-hosting.
 */
class HtmxAssetServingIT : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var serverProperties: ServerProperties

    @Test
    fun `rendered HTML self-hosts htmx and drops the CDN`() = fixture {
        given {
            tenant("Htmx Asset Test Tenant")
        }

        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

            val body = response.body!!
            // htmx is loaded from our own resources with a content hash:
            // e.g. /js/vendor/htmx.min-abc123def456.js
            assertThat(body).matches(
                Regex(
                    ".*src=\"/js/vendor/htmx\\.min-[a-f0-9]+\\.js\".*",
                    RegexOption.DOT_MATCHES_ALL,
                ).toPattern(),
            )
            // No third-party CDN reference remains.
            assertThat(body).doesNotContain("unpkg.com")
        }
    }

    @Test
    fun `vendored htmx is served with a long-lived cache header`() {
        val response = restTemplate.getForEntity("/js/vendor/htmx.min.js", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val cacheControl = response.headers.cacheControl
        assertThat(cacheControl).isNotNull()
        assertThat(cacheControl).contains("max-age=31536000")
        assertThat(cacheControl).contains("public")
    }

    @Test
    fun `vendored Scalar API-reference bundle is served (no CDN)`() {
        val response = restTemplate.getForEntity("/js/vendor/scalar-api-reference.js", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.cacheControl).contains("max-age=31536000")
    }

    @Test
    fun `compression config lists the content type javascript is actually served as`() {
        // `.js`/`.mjs` are served as `text/javascript` by both Tomcat's
        // DefaultServlet and Spring's MediaTypeFactory. A config listing only
        // `application/javascript` (as this project's did) never matches a real
        // response, so JS ships uncompressed. This is a cheap config guard; the
        // end-to-end test below is the one that proves the wire behavior.
        val compression = serverProperties.compression
        assertThat(compression.enabled).isTrue()
        assertThat(compression.mimeTypes).contains("text/javascript")
    }

    @Test
    fun `javascript assets are actually served gzip-compressed over the wire`() {
        // Use the JDK HttpClient rather than TestRestTemplate: it neither adds
        // `Accept-Encoding` on its own nor transparently decompresses, so the
        // `Content-Encoding` response header survives for us to assert on.
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${restTemplate.rootUri}/js/vendor/htmx.min.js"))
            .header("Accept-Encoding", "gzip")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

        assertThat(response.statusCode()).isEqualTo(200)
        // Document the actual served content type — the root cause of the gap:
        // the compression allow-list must include *this* value, not the legacy
        // `application/javascript`.
        assertThat(response.headers().firstValue("Content-Type"))
            .hasValueSatisfying { assertThat(it).startsWith("text/javascript") }
        // The payload came back gzip-encoded (htmx.min.js is well over the
        // 1 KB `min-response-size` threshold).
        assertThat(response.headers().firstValue("Content-Encoding"))
            .hasValue("gzip")
        // And it really is valid gzip that decodes to the script.
        val decoded = GZIPInputStream(response.body().inputStream()).readBytes()
        assertThat(decoded.size).isGreaterThan(response.body().size)
        assertThat(String(decoded)).contains("htmx")
    }
}
