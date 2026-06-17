package app.epistola.suite.fonts

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import tools.jackson.databind.ObjectMapper

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
            val faces = inter["variants"].values().map { it["weight"].asInt() to it["italic"].asBoolean() }
            assertThat(faces)
                .containsExactlyInAnyOrder(400 to false, 700 to false, 400 to true, 700 to true)
            assertThat(inter["css"]["family"].asString()).isEqualTo("epistola-system-inter")
            val regularFace = inter["css"]["faces"].values()
                .first { it["weight"].asInt() == 400 && !it["italic"].asBoolean() }
            assertThat(regularFace["url"].asString())
                .isEqualTo("/tenants/${testTenant.id}/fonts/system/inter/400/false/content")
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
                "/tenants/${testTenant.id}/fonts/system/inter/400/false/content",
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
                "/tenants/${testTenant.id}/fonts/system/does-not-exist/400/false/content",
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `GET new over HTMX pushes the upload URL`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Font Tenant")
        }

        whenever {
            val headers = HttpHeaders().apply {
                set("HX-Request", "true")
                set("HX-Current-URL", "/tenants/${testTenant.id}/fonts")
            }
            restTemplate.exchange(
                "/tenants/${testTenant.id}/fonts/new",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Upload forms use `?upload`, not `?create`.
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${testTenant.id}/fonts?upload")
        }
    }

    @Test
    fun `GET list with upload renders the dialog markup for deep linking`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Font Tenant")
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/fonts?upload", String::class.java)
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("create-font-dialog")
            assertThat(response.body).contains("data-create-dialog")
            assertThat(response.body).contains("data-dialog-param=\"upload\"")
        }
    }
}
