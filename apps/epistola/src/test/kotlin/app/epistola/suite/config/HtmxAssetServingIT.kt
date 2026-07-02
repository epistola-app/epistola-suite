package app.epistola.suite.config

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.http.HttpStatus

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
    fun `response compression is enabled for javascript`() {
        val compression = serverProperties.compression
        assertThat(compression.enabled).isTrue()
        assertThat(compression.mimeTypes).contains("application/javascript")
    }
}
