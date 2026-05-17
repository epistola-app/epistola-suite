package app.epistola.suite.fonts

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import tools.jackson.databind.ObjectMapper

@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class FontRoutesTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `search returns the eight bundled system fonts for a fresh tenant`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Font Tenant")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/fonts/search?catalog=default",
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

            val body = objectMapper.readTree(response.body)
            assertThat(body.isArray).isTrue()
            val slugs: List<String> = body.values().map { it["slug"].asString() }
            assertThat(slugs).containsExactlyInAnyOrder(
                "inter",
                "source-sans-3",
                "roboto",
                "lato",
                "source-serif-4",
                "merriweather",
                "lora",
                "jetbrains-mono",
            )

            val inter = body.values().first { it["slug"].asString() == "inter" }
            assertThat(inter["name"].asString()).isEqualTo("Inter")
            assertThat(inter["kind"].asString()).isEqualTo("sans")
            assertThat(inter["catalogKey"].asString()).isEqualTo("system")
            val variants: List<String> = inter["variants"].values().map { it.asString() }
            assertThat(variants)
                .containsExactlyInAnyOrder("regular", "bold", "italic", "bold_italic")
            assertThat(inter["css"]["family"].asString()).isEqualTo("epistola-system-inter")
            assertThat(inter["css"]["urls"]["regular"].asString())
                .isEqualTo("/tenants/${testTenant.id}/fonts/system/inter/regular/content")
        }
    }

    @Test
    fun `content returns the system inter regular face as font ttf`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Font Tenant")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/fonts/system/inter/regular/content",
                ByteArray::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<ByteArray>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.contentType.toString()).isEqualTo("font/ttf")
            assertThat(response.headers.cacheControl).isEqualTo("public, max-age=31536000, immutable")
            assertThat(response.body).isNotNull()
            assertThat(response.body!!.size).isGreaterThan(0)
        }
    }

    @Test
    fun `content returns 404 for a missing font`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Font Tenant")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/fonts/system/does-not-exist/regular/content",
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }
}
