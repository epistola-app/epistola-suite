package app.epistola.suite.fonts

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/**
 * Server-side contract for the font UPLOAD form converted onto the dialog
 * groundwork. Uploads take the upload-family error track: because a browser
 * cannot repopulate `<input type=file>` (and the face rows have no hydration),
 * validation errors OOB-swap only the per-field `field-error` spans and never
 * re-render the form body, keeping the user's files + rows in place. Mirrors
 * `EnvironmentHandlerHtmxTest` for the shared dialog branches; the JSON/editor
 * contract stays covered by `FontUploadHandlerTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FontDialogHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    private fun ttf(): ByteArray = resourceLoader
        .getResource("classpath:epistola/fonts/inter/inter-Regular.ttf")
        .contentAsByteArray

    private fun facePart(name: String) = HttpEntity(
        object : ByteArrayResource(ttf()) {
            override fun getFilename(): String = name
        },
        HttpHeaders().apply { contentType = MediaType.parseMediaType("font/ttf") },
    )

    private fun htmxHeaders() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun htmxMultipartHeaders() = HttpHeaders().apply {
        contentType = MediaType.MULTIPART_FORM_DATA
        set("HX-Request", "true")
    }

    @Test
    fun `HTMX GET new returns the dialog fragment with the upload form`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font New Dialog") }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/fonts/new",
                HttpMethod.GET,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("""id="upload-font-dialog"""")
            assertThat(response.body).contains("ep-dialog")
            assertThat(response.body).contains("""id="upload-font-form"""")
            assertThat(response.body).contains("""name="slug"""")
            assertThat(response.body).contains("""type="file"""")
            assertThat(response.body).contains("multipart/form-data")
            // A fragment, not the whole page.
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `non-HTMX GET new renders the list page with the dialog embedded and open`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font New DirectNav") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/fonts/new",
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("<html")
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).contains("""id="upload-font-dialog"""")
            assertThat(response.body).contains("""id="upload-font-form"""")
        }
    }

    @Test
    fun `plain list route does not embed the upload dialog`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font Plain List") }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/fonts", String::class.java)
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).doesNotContain("""id="upload-font-dialog"""")
        }
    }

    @Test
    fun `HTMX POST invalid OOB-swaps the field error span without re-rendering the form body`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font Create Invalid") }

        whenever {
            // Everything valid EXCEPT the missing display name → a single `name`
            // field error. A face file is included, so this is not a faces error.
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("slug", "acme-sans")
            payload.add("kind", "sans")
            payload.add("catalog", "default")
            payload.add("file", facePart("acme-sans-regular.ttf"))
            payload.add("weight", "400")
            payload.add("italic", "false")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts",
                HttpEntity(payload, htmxMultipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // OOB-only: no primary swap, no retarget, dialog stays open.
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.headers.getFirst("HX-Retarget")).isNull()
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            // The per-field span carries the message …
            assertThat(response.body).contains("""id="font-name-error"""")
            assertThat(response.body).contains("Display name is required")
            // … and the form BODY is NOT in the response (file inputs preserved).
            assertThat(response.body).doesNotContain("""id="upload-font-form"""")
            assertThat(response.body).doesNotContain("""type="file"""")
        }
    }

    @Test
    fun `HTMX POST accumulates multiple field errors into their spans at once`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font Multi Error") }

        whenever {
            // Missing name AND missing catalog AND no face file → three errors in
            // one response. The point of accumulating (vs first-error-wins): the
            // user sees every problem at once.
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("slug", "acme-sans")
            payload.add("kind", "sans")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts",
                HttpEntity(payload, htmxMultipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            // All three messages arrive together, each in its own span.
            assertThat(response.body).contains("""id="font-name-error"""")
            assertThat(response.body).contains("Display name is required")
            assertThat(response.body).contains("""id="font-catalog-error"""")
            assertThat(response.body).contains("Catalog is required")
            assertThat(response.body).contains("""id="font-faces-error"""")
            assertThat(response.body).contains("At least one face file is required")
        }
    }

    @Test
    fun `HTMX POST valid closes the dialog and refreshes the grid OOB with the new family`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font Create Valid") }

        whenever {
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("slug", "acme-sans")
            payload.add("name", "Acme Sans")
            payload.add("kind", "sans")
            payload.add("catalog", "default")
            payload.add("file", facePart("acme-sans-regular.ttf"))
            payload.add("weight", "400")
            payload.add("italic", "false")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts",
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
                .isEqualTo("/tenants/${testTenant.id}/fonts")
            // The OOB grid fragment carries its id + hx-swap-oob and the new family.
            assertThat(response.body).contains("""id="font-grid-items"""")
            assertThat(response.body).contains("hx-swap-oob=\"outerHTML\"")
            assertThat(response.body).contains("Acme Sans")
        }
    }
}
