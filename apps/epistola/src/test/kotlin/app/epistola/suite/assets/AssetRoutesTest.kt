package app.epistola.suite.assets

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

class AssetRoutesTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `POST assets returns validation error for unsupported media type`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Asset Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            headers.accept = listOf(MediaType.APPLICATION_JSON)

            val payload = LinkedMultiValueMap<String, Any>()
            payload.add(
                "file",
                HttpEntity(
                    object : ByteArrayResource("not-an-image".toByteArray()) {
                        override fun getFilename(): String = "report.xlsx"
                    },
                    HttpHeaders().apply {
                        contentType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    },
                ),
            )
            payload.add("catalog", "default")

            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/images",
                HttpEntity(payload, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // The editor (Accept: json, non-HTMX) gets RFC 9457 problem+json from the
            // shared filter; its `detail` carries the message uploadAsset reads.
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.headers.contentType!!.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
            assertThat(response.body).contains("Unsupported asset media type")
            assertThat(response.body).contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }
    }

    @Test
    fun `HTMX upload without a catalog swaps the OOB catalog error and keeps the form`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Asset OOB Catalog Tenant") }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            headers.set("HX-Request", "true")

            val payload = LinkedMultiValueMap<String, Any>()
            payload.add(
                "file",
                HttpEntity(
                    object : ByteArrayResource(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) {
                        override fun getFilename(): String = "logo.png"
                    },
                    HttpHeaders().apply { contentType = MediaType.IMAGE_PNG },
                ),
            )
            // No catalog field.

            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/images",
                HttpEntity(payload, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // HTMX validation errors come back 200 with OOB error spans, not a 4xx.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).contains("id=\"asset-error-catalog\"")
            assertThat(body).contains("hx-swap-oob=\"true\"")
            assertThat(body).contains("data-error=\"true\"")
            // The form is NOT re-rendered — the chosen file must survive.
            assertThat(body).doesNotContain("id=\"asset-upload-form\"")
        }
    }

    @Test
    fun `HTMX dialog upload without a file renders the general error in the dialog region`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Asset Dialog File Tenant") }

        whenever {
            // The dialog form declares its error region via X-Epistola-Error-Region
            // (inherited from the dialog's hx-headers in the browser); the shared
            // filter renders the thrown error as an OOB swap into it.
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            headers.set("HX-Request", "true")
            headers.set("X-Epistola-Error-Region", "dialog-error")

            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("catalog", "default")
            // No file field.

            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/images",
                HttpEntity(payload, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // 200 + Reswap:none so HTMX applies the OOB without touching the form/file.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            val body = response.body!!
            assertThat(body).contains("id=\"dialog-error\"")
            assertThat(body).contains("hx-swap-oob=\"true\"")
            assertThat(body).contains("alert-error")
            assertThat(body).contains("No file provided")
        }
    }
}
