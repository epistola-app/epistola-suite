package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Regression for the template save encoding bug: saving a template whose text
 * carries diacritics stored mojibake instead of the original characters.
 *
 * Two save surfaces are covered, each replicating the exact wire shape a browser
 * produces:
 *
 *  - The **editor draft save** — a JSON `PUT` of the template model. The body is
 *    raw UTF-8 bytes under a bare `application/json` header (per RFC 8259 JSON is
 *    always UTF-8, so browsers send no charset parameter).
 *  - The **template create form** — an `application/x-www-form-urlencoded` `POST`
 *    whose `name` field carries diacritics, percent-encoded as UTF-8 under a bare
 *    form content type (the shape an HTML form on a UTF-8 page submits).
 *
 * Both must round-trip the characters unchanged. Loading worked all along because
 * stored bytes read back through the JSONB/Jackson (UTF-8) path untouched; only
 * the inbound decode was suspect.
 */
class TemplateDraftEncodingHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /** A spread of diacritics across scripts that all use UTF-8 multi-byte sequences. */
    private val diacritics = "Café — naïve Größe ñ ë œuvre Žluťoučký kůň úpěl ďábelské ódy"

    @Test
    fun `PUT draft preserves diacritics when the body is UTF-8 under a bare application json content type`() = fixture {
        data class Seed(val tenantKey: String, val catalogKey: String, val templateKey: String, val variantId: VariantId)
        lateinit var seed: Seed

        given {
            seed = withMediator {
                val tenant: Tenant = createTenant("Draft Encoding")
                val tenantId = TenantId(tenant.id)
                val templateKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
                CreateDocumentTemplate(id = templateId, name = "Diacritics Letter").execute()
                val variantId = VariantId(VariantKey.of("initial"), templateId)
                Seed(tenantId.key.value, templateId.catalogKey.value, templateKey.value, variantId)
            }
        }

        whenever {
            val body = objectMapper.writeValueAsString(mapOf("templateModel" to documentWithText(diacritics)))
            // Mirror the browser: raw UTF-8 bytes with `application/json` and NO charset
            // parameter. Sending bytes (not a String) keeps the client converter from
            // re-encoding, so the server sees exactly what the editor's fetch sends.
            val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
            val entity = HttpEntity(body.toByteArray(StandardCharsets.UTF_8), headers)
            restTemplate.exchange(
                "/tenants/${seed.tenantKey}/templates/${seed.catalogKey}/${seed.templateKey}/variants/${seed.variantId.key}/draft",
                HttpMethod.PUT,
                entity,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

            val draft = withMediator { GetDraft(variantId = seed.variantId).query() }
            assertThat(draft).isNotNull
            val stored = draft!!.templateModel.nodes["text-1"]?.props?.get("content")
            assertThat(stored).isEqualTo(diacritics)
        }
    }

    @Test
    fun `POST create form preserves diacritics in the template name when the body is UTF-8 form-encoded`() = fixture {
        data class Seed(val tenantKey: String, val templateId: TemplateId)
        lateinit var seed: Seed
        val slug = "diacritics-form-${TestIdHelpers.nextTemplateId().value}".take(50).trimEnd('-')

        given {
            seed = withMediator {
                val tenant: Tenant = createTenant("Form Encoding")
                val tenantId = TenantId(tenant.id)
                Seed(tenantId.key.value, TemplateId(TemplateKey.of(slug), CatalogId.default(tenantId)))
            }
        }

        whenever {
            // Replicate a real HTML form submit from a UTF-8 page: a percent-encoded
            // body (UTF-8 octets) under `application/x-www-form-urlencoded` with NO
            // charset parameter. Send as raw bytes so the client doesn't re-encode.
            val name = URLEncoder.encode(diacritics, StandardCharsets.UTF_8)
            val body = "catalog=${seed.templateId.catalogKey.value}&slug=$slug&name=$name"
            val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
            val entity = HttpEntity(body.toByteArray(StandardCharsets.US_ASCII), headers)
            restTemplate.exchange(
                "/tenants/${seed.tenantKey}/templates",
                HttpMethod.POST,
                entity,
                String::class.java,
            )
        }

        then {
            val stored = withMediator { GetDocumentTemplate(id = seed.templateId).query() }
            assertThat(stored).isNotNull
            // Before the fix this read mojibake (e.g. "CafÃ©") because the servlet
            // decoded the form body as ISO-8859-1.
            assertThat(stored!!.name).isEqualTo(diacritics)
        }
    }

    /** Minimal document: root → single text node carrying [content]. */
    private fun documentWithText(content: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "text-1" to Node(id = "text-1", type = "text", slots = emptyList(), props = mapOf("content" to content)),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("text-1")),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
