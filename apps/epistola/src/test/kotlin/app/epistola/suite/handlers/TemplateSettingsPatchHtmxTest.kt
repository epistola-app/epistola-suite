// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/**
 * The settings-tab controls (theme select, name input, PDF/A toggle) are
 * native HTMX controls: each `hx-patch`es its endpoint form-encoded and swaps
 * the returned fragment in place. These tests assert that server contract —
 * form-param parsing, persisted state, and the fragment (incl. OOB parts)
 * coming back.
 */
class TemplateSettingsPatchHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `PATCH theme with a catalog-qualified value assigns it and returns the fragment with the option selected`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Theme")
                val tenantId = TenantId(t.id)
                CreateTheme(
                    id = ThemeId(ThemeKey.of("brand-theme"), CatalogId.default(tenantId)),
                    name = "Brand Theme",
                ).execute()
                val tplKey = TestIdHelpers.nextTemplateId()
                CreateDocumentTemplate(
                    id = TemplateId(tplKey, CatalogId.default(tenantId)),
                    name = "Invoice",
                ).execute()
                t to tplKey.value
            }
            tenant = seed.first
            templateKey = seed.second
        }

        whenever {
            patchForm(
                "/tenants/${tenant.id}/templates/default/$templateKey/theme",
                "themeId" to "default/brand-theme",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).contains("id=\"theme-section\"")
            val option = Regex("<option[^>]*default/brand-theme[^>]*>").find(body)?.value
            assertThat(option).isNotNull()
            assertThat(option).contains("selected")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(app.epistola.suite.common.ids.TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.themeKey).isEqualTo(ThemeKey.of("brand-theme"))
        }
    }

    @Test
    fun `PATCH theme with a blank value clears the assignment`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Theme Clear")
                val tenantId = TenantId(t.id)
                CreateTheme(
                    id = ThemeId(ThemeKey.of("brand-theme"), CatalogId.default(tenantId)),
                    name = "Brand Theme",
                ).execute()
                val tplKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(tplKey, CatalogId.default(tenantId))
                CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
                app.epistola.suite.templates.commands.UpdateDocumentTemplate(
                    id = templateId,
                    themeId = ThemeKey.of("brand-theme"),
                    themeCatalogKey = app.epistola.suite.common.ids.CatalogKey.DEFAULT,
                ).execute()
                t to tplKey.value
            }
            tenant = seed.first
            templateKey = seed.second
        }

        whenever {
            patchForm(
                "/tenants/${tenant.id}/templates/default/$templateKey/theme",
                "themeId" to "",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // No theme option may come back selected once the assignment is cleared.
            assertThat(response.body).doesNotContainPattern("<option[^>]*default/brand-theme[^>]*selected")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(app.epistola.suite.common.ids.TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.themeKey).isNull()
        }
    }

    @Test
    fun `PATCH name renames and returns the input fragment plus the OOB title sync`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Name")
                val tplKey = TestIdHelpers.nextTemplateId()
                CreateDocumentTemplate(
                    id = TemplateId(tplKey, CatalogId.default(TenantId(t.id))),
                    name = "Invoice",
                ).execute()
                t to tplKey.value
            }
            tenant = seed.first
            templateKey = seed.second
        }

        whenever {
            patchForm(
                "/tenants/${tenant.id}/templates/default/$templateKey/name",
                "name" to "Quarterly Invoice",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // The main swap: the input re-rendered with the new name.
            assertThat(body).contains("data-template-name-input")
            assertThat(body).contains("value=\"Quarterly Invoice\"")
            // The OOB companion: header title sync (badge span untouched).
            assertThat(body).contains("hx-swap-oob=\"textContent:[data-testid='page-title'] > span:first-child\"")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(app.epistola.suite.common.ids.TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.name).isEqualTo("Quarterly Invoice")
        }
    }

    @Test
    fun `PATCH name with a blank value is a silent no-op that re-renders the current name`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Name Blank")
                val tplKey = TestIdHelpers.nextTemplateId()
                CreateDocumentTemplate(
                    id = TemplateId(tplKey, CatalogId.default(TenantId(t.id))),
                    name = "Invoice",
                ).execute()
                t to tplKey.value
            }
            tenant = seed.first
            templateKey = seed.second
        }

        whenever {
            patchForm(
                "/tenants/${tenant.id}/templates/default/$templateKey/name",
                "name" to "   ",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("value=\"Invoice\"")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(app.epistola.suite.common.ids.TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.name).isEqualTo("Invoice")
        }
    }

    /** Form-encoded PATCH with the HX-Request header — what an hx-patch control sends. */
    private fun patchForm(url: String, vararg params: Pair<String, String>): ResponseEntity<String> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
        headers.set("HX-Request", "true")
        val form = LinkedMultiValueMap<String, String>()
        params.forEach { (k, v) -> form.add(k, v) }
        return restTemplate.exchange(url, HttpMethod.PATCH, HttpEntity(form, headers), String::class.java)
    }
}
