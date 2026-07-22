package app.epistola.suite.assets

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/**
 * Server-side contract for the image UPLOAD form converted onto the dialog
 * groundwork (mirrors `FontDialogHandlerHtmxTest`). Validation errors take the
 * upload-family OOB track — only the per-field `field-error` spans swap, the
 * form body (and the chosen file) is left in place. The JSON/editor contract
 * stays covered by `AssetRoutesTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageDialogHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun pngPart(name: String) = HttpEntity(
        object : ByteArrayResource("fake-png-bytes".toByteArray()) {
            override fun getFilename(): String = name
        },
        HttpHeaders().apply { contentType = MediaType.IMAGE_PNG },
    )

    private fun htmxHeaders() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun htmxMultipartHeaders() = HttpHeaders().apply {
        contentType = MediaType.MULTIPART_FORM_DATA
        set("HX-Request", "true")
    }

    @Test
    fun `HTMX GET new returns the dialog fragment with the upload form`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Image New Dialog") }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/images/new",
                HttpMethod.GET,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("""id="upload-image-dialog"""")
            assertThat(response.body).contains("ep-dialog")
            assertThat(response.body).contains("""id="upload-image-form"""")
            assertThat(response.body).contains("""type="file"""")
            assertThat(response.body).contains("multipart/form-data")
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `non-HTMX GET new renders the list page with the dialog embedded and open`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Image New DirectNav") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/images/new",
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("<html")
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).contains("""id="upload-image-dialog"""")
            assertThat(response.body).contains("""id="upload-image-form"""")
        }
    }

    @Test
    fun `plain list route does not embed the upload dialog`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Image Plain List") }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/images", String::class.java)
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).doesNotContain("""id="upload-image-dialog"""")
        }
    }

    @Test
    fun `HTMX POST with no file OOB-swaps the file error span without re-rendering the form body`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Image Create No File") }

        whenever {
            // catalog present, but no file part → a single `file` field error.
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("catalog", "default")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/images",
                HttpEntity(payload, htmxMultipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.headers.getFirst("HX-Retarget")).isNull()
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            // The file span carries the message …
            assertThat(response.body).contains("""id="image-file-error"""")
            assertThat(response.body).contains("No file provided")
            // … and the form body is NOT re-rendered.
            assertThat(response.body).doesNotContain("""id="upload-image-form"""")
            assertThat(response.body).doesNotContain("""type="file"""")
        }
    }

    @Test
    fun `HTMX POST with malformed catalog OOB-swaps the catalog error span without re-rendering the form body`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Image Create Bad Catalog") }

        whenever {
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("catalog", "Bad Catalog!")
            payload.add("file", pngPart("diagram.png"))
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/images",
                HttpEntity(payload, htmxMultipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.headers.getFirst("HX-Retarget")).isNull()
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            assertThat(response.body).contains("""id="image-catalog-error"""")
            assertThat(response.body).contains("Invalid catalog ID format")
            assertThat(response.body).doesNotContain("""id="upload-image-form"""")
            assertThat(response.body).doesNotContain("""type="file"""")
        }
    }

    @Test
    fun `HTMX POST valid closes the dialog and refreshes the grid OOB with the new image`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Image Create Valid") }

        whenever {
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("catalog", "default")
            payload.add("file", pngPart("diagram.png"))
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/images",
                HttpEntity(payload, htmxMultipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Trigger"))
                .isEqualTo("""{"closeDialog": null, "dialogSuccess": null}""")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.headers.getFirst("HX-Replace-Url"))
                .isEqualTo("/tenants/${testTenant.id}/images")
            // The OOB grid region carries its id + hx-swap-oob and the new image.
            assertThat(response.body).contains("""id="asset-list"""")
            assertThat(response.body).contains("hx-swap-oob=\"outerHTML\"")
            assertThat(response.body).contains("diagram.png")
        }
    }
}
