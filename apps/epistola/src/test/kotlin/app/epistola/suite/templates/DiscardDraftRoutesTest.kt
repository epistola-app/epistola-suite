package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class DiscardDraftRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `POST discard removes the draft and signals a redirect`() = fixture {
        lateinit var testTenant: Tenant
        lateinit var templateId: String
        lateinit var variantKey: String
        lateinit var variantId: VariantId

        given {
            testTenant = tenant("Discard Test")
            val template = template(testTenant, "Invoice Template")
            templateId = template.id.value
            val tplId = TemplateId(template.id, CatalogId.default(TenantId(testTenant.id)))
            variantKey = "initial"
            variantId = VariantId(VariantKey.of(variantKey), tplId)

            // Publish the auto-created v1 draft, then open a fresh draft (v2) on top.
            val draft = GetDraft(variantId).query()!!
            PublishVersion(versionId = VersionId(draft.id, variantId)).execute()
            CreateVersion(variantId = variantId).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            restTemplate.exchange(
                "/tenants/${testTenant.id}/templates/default/$templateId/variants/$variantKey/draft/discard",
                HttpMethod.POST,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Redirect"))
                .isEqualTo("/tenants/${testTenant.id}/templates/default/$templateId")
            // The draft is gone; the published version remains.
            assertThat(mediator.query(GetDraft(variantId))).isNull()
        }
    }

    @Test
    fun `POST discard shows an error when there is no published version`() = fixture {
        lateinit var testTenant: Tenant
        lateinit var templateId: String
        lateinit var variantKey: String
        lateinit var variantId: VariantId

        given {
            testTenant = tenant("Discard No-Publish Test")
            val template = template(testTenant, "Invoice Template")
            templateId = template.id.value
            val tplId = TemplateId(template.id, CatalogId.default(TenantId(testTenant.id)))
            variantKey = "initial"
            variantId = VariantId(VariantKey.of(variantKey), tplId)
            // Fresh template: a draft v1 exists but nothing has been published.
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            restTemplate.exchange(
                "/tenants/${testTenant.id}/templates/default/$templateId/variants/$variantKey/draft/discard",
                HttpMethod.POST,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Redirect")).isNull()
            assertThat(response.body).contains("alert-error")
            assertThat(response.body).contains("no published version")
            // The draft is left intact.
            assertThat(mediator.query(GetDraft(variantId))).isNotNull
        }
    }
}
