// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
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
 * coming back. Since #477 the successful responses also carry a corner
 * success notice as an OOB fragment (`hx-swap-oob="afterbegin:#notices"`);
 * the happy-path tests pin its presence and message, and the rename no-op
 * pins its absence — a save that didn't happen must not be confirmed.
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
            // The success notice rides along as an OOB fragment.
            assertThat(body).contains("hx-swap-oob=\"afterbegin:#notices\"")
            assertThat(body).contains("Default theme updated.")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
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
                UpdateDocumentTemplate(
                    id = templateId,
                    themeId = ThemeKey.of("brand-theme"),
                    themeCatalogKey = CatalogKey.DEFAULT,
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
            assertThat(response.body).contains("Default theme cleared.")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.themeKey).isNull()
        }
    }

    @Test
    fun `PATCH theme with a malformed value returns 400 without touching the assignment`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Theme Malformed")
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
            // The select only ever emits "catalogKey/themeKey" — a slashless
            // value can only come from a tampered request.
            patchForm(
                "/tenants/${tenant.id}/templates/default/$templateKey/theme",
                "themeId" to "brand-theme",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
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
            assertThat(body).contains("hx-swap-oob=\"innerHTML:#page-title-text\"")
            // A real rename is confirmed with a success notice.
            assertThat(body).contains("hx-swap-oob=\"afterbegin:#notices\"")
            assertThat(body).contains("Template renamed.")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
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
            // Nothing was saved, so nothing is confirmed: no notice on the no-op.
            assertThat(response.body).doesNotContain("afterbegin:#notices")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.name).isEqualTo("Invoice")
        }
    }

    @Test
    fun `PATCH pdfa with the checkbox param enables PDF A and returns the checked fragment`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Pdfa On")
                val tplKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(tplKey, CatalogId.default(TenantId(t.id)))
                CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
                // pdfa_enabled defaults to TRUE; start from false so this test
                // proves the enable transition rather than the column default.
                UpdateDocumentTemplate(id = templateId, pdfaEnabled = false).execute()
                t to tplKey.value
            }
            tenant = seed.first
            templateKey = seed.second
        }

        whenever {
            patchForm(
                "/tenants/${tenant.id}/templates/default/$templateKey/pdfa",
                "pdfaEnabled" to "on",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).contains("id=\"output-settings-section\"")
            val toggleTag = Regex("<input[^>]*id=\"pdfa-toggle\"[^>]*>").find(body)?.value
            assertThat(toggleTag).isNotNull()
            assertThat(toggleTag).contains("checked")
            assertThat(body).contains("PDF/A output enabled.")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.pdfaEnabled).isTrue()
        }
    }

    @Test
    fun `PATCH pdfa without the checkbox param disables PDF A`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Pdfa Off")
                val tplKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(tplKey, CatalogId.default(TenantId(t.id)))
                CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
                UpdateDocumentTemplate(
                    id = templateId,
                    pdfaEnabled = true,
                ).execute()
                t to tplKey.value
            }
            tenant = seed.first
            templateKey = seed.second
        }

        whenever {
            // An unchecked checkbox submits no param at all — the request body is empty.
            patchForm("/tenants/${tenant.id}/templates/default/$templateKey/pdfa")
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val toggleTag = Regex("<input[^>]*id=\"pdfa-toggle\"[^>]*>").find(response.body!!)?.value
            assertThat(toggleTag).isNotNull()
            assertThat(toggleTag).doesNotContain("checked")
            assertThat(response.body).contains("PDF/A output disabled.")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.pdfaEnabled).isFalse()
        }
    }

    @Test
    fun `PATCH pdfa with an explicit false value disables PDF A`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Pdfa False")
                val tplKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(tplKey, CatalogId.default(TenantId(t.id)))
                CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
                UpdateDocumentTemplate(
                    id = templateId,
                    pdfaEnabled = true,
                ).execute()
                t to tplKey.value
            }
            tenant = seed.first
            templateKey = seed.second
        }

        whenever {
            // The hidden-false-companion convention: the param present with the
            // literal value "false" must read as disable, not as presence=enable.
            patchForm(
                "/tenants/${tenant.id}/templates/default/$templateKey/pdfa",
                "pdfaEnabled" to "false",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val toggleTag = Regex("<input[^>]*id=\"pdfa-toggle\"[^>]*>").find(response.body!!)?.value
            assertThat(toggleTag).isNotNull()
            assertThat(toggleTag).doesNotContain("checked")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.pdfaEnabled).isFalse()
        }
    }

    @Test
    fun `PATCH theme with a well-formed but nonexistent theme returns 400 with a detail`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Theme Missing")
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
            // Not only a tamper case: a stale page whose theme was deleted in
            // another tab posts exactly this. Must be a clean 400 with a detail
            // the global error handling can show — not an FK-violation 500.
            patchForm(
                "/tenants/${tenant.id}/templates/default/$templateKey/theme",
                "themeId" to "default/does-not-exist",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.body).contains("The selected theme no longer exists")

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.themeKey).isNull()
        }
    }

    @Test
    fun `PATCH name over the length limit returns 400 and keeps the current name`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Name Long")
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
            // maxlength=100 is client-side only. The command's init validation
            // throws ValidationException; the UI exception filter must map it to
            // a 400 problem, not an opaque 500.
            patchForm(
                "/tenants/${tenant.id}/templates/default/$templateKey/name",
                "name" to "x".repeat(500),
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.name).isEqualTo("Invoice")
        }
    }

    @Test
    fun `PATCH name for an unknown template returns 404`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = withMediator { createTenant("Settings Patch Unknown") }
        }

        whenever {
            patchForm(
                "/tenants/${tenant.id}/templates/default/no-such-template/name",
                "name" to "Anything",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `PATCH name scoped to the wrong tenant returns 404`() = fixture {
        lateinit var tenantA: Tenant
        var templateKeyB = ""

        given {
            val seed = withMediator {
                val a = createTenant("Settings Patch Tenant A")
                val b = createTenant("Settings Patch Tenant B")
                val tplKey = TestIdHelpers.nextTemplateId()
                CreateDocumentTemplate(
                    id = TemplateId(tplKey, CatalogId.default(TenantId(b.id))),
                    name = "Tenant B Template",
                ).execute()
                a to tplKey.value
            }
            tenantA = seed.first
            templateKeyB = seed.second
        }

        whenever {
            // A template key that exists — but in another tenant. Resolution is
            // tenant-scoped, so this must be indistinguishable from not-found.
            patchForm(
                "/tenants/${tenantA.id}/templates/default/$templateKeyB/name",
                "name" to "Hijacked",
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `PATCH name without the HTMX header falls back to the detail-page redirect`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Non Htmx")
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
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            val form = LinkedMultiValueMap<String, String>()
            form.add("name", "Renamed Without Htmx")
            restTemplate.exchange(
                "/tenants/${tenant.id}/templates/default/$templateKey/name",
                HttpMethod.PATCH,
                HttpEntity(form, headers),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            // The onNonHtmx branch redirects to the template detail page. The
            // test client may follow the redirect, so accept either the 3xx
            // itself or the followed 200 — never an error.
            if (response.statusCode.is3xxRedirection) {
                assertThat(response.headers.location?.path)
                    .endsWith("/templates/default/$templateKey")
            } else {
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            }

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.name).isEqualTo("Renamed Without Htmx")
        }
    }

    @Test
    fun `PATCH endpoints return 403 for a viewer without TEMPLATE_EDIT`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = ""

        given {
            val seed = withMediator {
                val t = createTenant("Settings Patch Viewer")
                val tenantId = TenantId(t.id)
                CreateTheme(
                    id = ThemeId(ThemeKey.of("brand-theme"), CatalogId.default(tenantId)),
                    name = "Brand Theme",
                ).execute()
                val tplKey = TestIdHelpers.nextTemplateId()
                val templateId = TemplateId(tplKey, CatalogId.default(tenantId))
                CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
                // pdfa_enabled defaults to TRUE for new templates; flip it off so
                // the viewer's "on" PATCH below attempts a real state change and
                // the final assertion proves no write happened.
                UpdateDocumentTemplate(id = templateId, pdfaEnabled = false).execute()
                t to tplKey.value
            }
            tenant = seed.first
            templateKey = seed.second
        }

        whenever {
            // Disabling the controls in the template is cosmetic; the endpoints
            // themselves must reject a CONTENT_VIEWER. Note the shapes differ
            // slightly on the way in (updateName reads via TEMPLATE_VIEW first,
            // updateTheme pre-checks via THEME_VIEW) — all three must still
            // converge on a 403.
            val base = "/tenants/${tenant.id}/templates/default/$templateKey"
            listOf(
                patchFormAs("CONTENT_VIEWER", "$base/name", "name" to "Hijacked"),
                patchFormAs("CONTENT_VIEWER", "$base/theme", "themeId" to "default/brand-theme"),
                patchFormAs("CONTENT_VIEWER", "$base/pdfa", "pdfaEnabled" to "on"),
            )
        }

        then {
            val responses = result<List<ResponseEntity<String>>>()
            responses.forEach { response ->
                assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
            }

            val updated = withMediator {
                GetDocumentTemplate(
                    id = TemplateId(TemplateKey.of(templateKey), CatalogId.default(TenantId(tenant.id))),
                ).query()
            }
            assertThat(updated!!.name).isEqualTo("Invoice")
            assertThat(updated.themeKey).isNull()
            assertThat(updated.pdfaEnabled).isFalse()
        }
    }

    /** Form-encoded PATCH with the HX-Request header — what an hx-patch control sends. */
    private fun patchForm(url: String, vararg params: Pair<String, String>): ResponseEntity<String> = patchFormAs(null, url, *params)

    /**
     * Like [patchForm], but the request runs with only the given comma-separated
     * [TenantRole][app.epistola.suite.security.TenantRole] names (the
     * X-Test-Tenant-Roles seam in TestSecurityContextConfiguration).
     */
    private fun patchFormAs(roles: String?, url: String, vararg params: Pair<String, String>): ResponseEntity<String> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
        headers.set("HX-Request", "true")
        if (roles != null) headers.set("X-Test-Tenant-Roles", roles)
        val form = LinkedMultiValueMap<String, String>()
        params.forEach { (k, v) -> form.add(k, v) }
        return restTemplate.exchange(url, HttpMethod.PATCH, HttpEntity(form, headers), String::class.java)
    }
}
