package app.epistola.suite.config

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class CacheBustingIntegrationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `rendered HTML contains content-hashed CSS URLs`() = fixture {
        given {
            tenant("Cache Test Tenant")
        }

        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

            val body = response.body!!
            // CSS URLs should contain a content hash: e.g. /css/main-abc123def456.css
            assertThat(body).matches(Regex(".*href=\"/css/main-[a-f0-9]+\\.css\".*", RegexOption.DOT_MATCHES_ALL).toPattern())
            assertThat(body).matches(
                Regex(".*href=\"/design-system/tokens-[a-f0-9]+\\.css\".*", RegexOption.DOT_MATCHES_ALL).toPattern(),
            )
        }
    }

    @Test
    fun `static CSS resources have long-lived Cache-Control headers`() {
        val response = restTemplate.getForEntity("/css/main.css", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val cacheControl = response.headers.cacheControl
        assertThat(cacheControl).isNotNull()
        assertThat(cacheControl).contains("max-age=31536000")
        assertThat(cacheControl).contains("public")
    }

    @Test
    fun `static JS resources have long-lived Cache-Control headers`() {
        val response = restTemplate.getForEntity("/js/pdf-preview.js", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val cacheControl = response.headers.cacheControl
        assertThat(cacheControl).isNotNull()
        assertThat(cacheControl).contains("max-age=31536000")
        assertThat(cacheControl).contains("public")
    }

    @Test
    fun `SVG icon references preserve fragment after hashing`() = fixture {
        given {
            tenant("SVG Test Tenant")
        }

        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

            val body = response.body!!
            // SVG sprite references should have hash AND preserve #fragment:
            // e.g. /design-system/icons-abc123.svg#icon-file-text
            assertThat(body).matches(
                Regex(
                    ".*href=\"/design-system/icons-[a-f0-9]+\\.svg#icon-[a-z-]+\".*",
                    RegexOption.DOT_MATCHES_ALL,
                ).toPattern(),
            )
        }
    }
}
