package app.epistola.suite

import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * The shared `UiExceptionFilter` is the single renderer for general/server errors a
 * handler **throws** (rather than hand-rendering). It content-negotiates one envelope:
 *
 *  - an HTMX form that declares an error region (`X-Epistola-Error-Region`, set on the
 *    create `<dialog>` via hx-headers) → **200 + `HX-Reswap: none` + an out-of-band swap**
 *    into that region, so the error shows inside the open modal and the form is untouched;
 *  - a data caller (`Accept: application/json`, e.g. the editor) → **RFC 9457 problem+json**
 *    whose `detail` carries the message.
 *
 * A *text* create form needs no special handling: it already lets operational exceptions
 * propagate, so once the dialog carries the region header the error just renders. Proven
 * here with a theme create into the read-only `system` catalog (CatalogReadOnlyException).
 */
class CentralErrorRenderingTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a text form's operational error renders as an OOB swap into the dialog region`() = fixture {
        lateinit var tenant: Tenant

        given { tenant = tenant("Theme Dialog Error Tenant") }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            headers.set("X-Epistola-Error-Region", "dialog-error")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("catalog", "system") // read-only → CatalogReadOnlyException
            formData.add("slug", "rogue-theme")
            formData.add("name", "Rogue Theme")
            restTemplate.postForEntity(
                "/tenants/${tenant.id}/themes",
                HttpEntity(formData, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            val body = response.body!!
            assertThat(body).contains("id=\"dialog-error\"")
            assertThat(body).contains("hx-swap-oob=\"true\"")
            assertThat(body).contains("alert-error")
            assertThat(body).contains("read-only")
        }
    }

    @Test
    fun `the same error for a data caller is RFC 9457 problem+json`() = fixture {
        lateinit var tenant: Tenant

        given { tenant = tenant("Theme JSON Error Tenant") }

        whenever {
            // No error-region header and Accept: json → the editor/programmatic contract.
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.accept = listOf(MediaType.APPLICATION_JSON)
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("catalog", "system")
            formData.add("slug", "rogue-theme")
            formData.add("name", "Rogue Theme")
            restTemplate.postForEntity(
                "/tenants/${tenant.id}/themes",
                HttpEntity(formData, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
            assertThat(response.headers.contentType!!.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
            assertThat(response.body).contains("\"detail\"")
            assertThat(response.body).contains("read-only")
        }
    }
}
