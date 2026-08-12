// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
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

/**
 * JSON contracts of the UI endpoints the editors' mount callbacks consume
 * (editor-boot.js, theme-editor-boot.js, pages/template-detail.js). These
 * responses are parsed by client fetch code that no browser test exercises
 * against a failure — a handler converted to return HTML (as the settings
 * controls were, deliberately, in #818) would break the editor at runtime
 * with nothing failing. Each test pins the response's content type and the
 * fields the JS actually reads.
 */
class EditorJsonContractTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun jsonHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        accept = listOf(MediaType.APPLICATION_JSON)
    }

    @Test
    fun `stencil listVersions returns the items envelope the version pickers read`() {
        val seeded = seedPublishedStencil("List Versions Contract")

        val response = restTemplate.exchange(
            "/tenants/${seeded.tenantKey}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/versions",
            HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).contains("application/json")
        // editor-boot.js listVersions/checkUpgrades read items[].version and items[].status.
        assertThat(response.body).contains("\"items\"")
        assertThat(response.body).contains("\"version\":1")
        assertThat(response.body).contains("\"status\":\"published\"")
    }

    @Test
    fun `stencil getVersion returns the content fields the Insert flow reads`() {
        val seeded = seedPublishedStencil("Get Version Contract")

        val response = restTemplate.exchange(
            "/tenants/${seeded.tenantKey}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/versions/1",
            HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).contains("application/json")
        // editor-boot.js getStencilVersion reads id, status, content, parameterSchema.
        assertThat(response.body).contains("\"id\":1")
        assertThat(response.body).contains("\"status\"")
        assertThat(response.body).contains("\"content\"")
    }

    @Test
    fun `stencil getVersion returns 404 for a missing version`() {
        val seeded = seedPublishedStencil("Get Version Missing")

        val response = restTemplate.exchange(
            "/tenants/${seeded.tenantKey}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/versions/99",
            HttpMethod.GET,
            HttpEntity<Void>(jsonHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `stencil publishVersion returns the version and status the editor reads`() {
        val seeded = seedDraftStencil("Publish Version Contract")

        val response = restTemplate.exchange(
            "/tenants/${seeded.tenantKey}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/versions/1/publish",
            HttpMethod.POST,
            HttpEntity("{}", jsonHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).contains("application/json")
        assertThat(response.body).contains("\"version\":1")
        assertThat(response.body).contains("\"status\":\"published\"")
    }

    @Test
    fun `theme update PATCH returns the JSON the theme editor's onSave reads`() {
        val tenantKey = withMediator {
            val tenant = createTenant("Theme Update Contract")
            val tenantId = TenantId(tenant.id)
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId.default(tenantId)), "Brand Theme").execute()
            tenant.id.value
        }

        val response = restTemplate.exchange(
            "/tenants/$tenantKey/themes/default/brand",
            HttpMethod.PATCH,
            HttpEntity("""{"name": "Brand Theme Renamed"}""", jsonHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).contains("application/json")
        // theme-editor-boot.js onSave reads result.name to update the page title.
        assertThat(response.body).contains("\"name\":\"Brand Theme Renamed\"")
        assertThat(response.body).contains("\"id\":\"brand\"")
    }

    @Test
    fun `contract draft PATCH on the UI route returns the success envelope the contract editor reads`() {
        val (tenantKey, templateKey) = withMediator {
            val tenant = createTenant("Contract Draft UI Route")
            val tenantId = TenantId(tenant.id)
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
            CreateDocumentTemplate(id = templateId, name = "Contract Draft Template").execute()
            tenant.id.value to templateId.key.value
        }

        val body = """{"dataModel": {"type": "object", "properties": {"customer": {"type": "string"}}}}"""
        val response = restTemplate.exchange(
            "/tenants/$tenantKey/templates/default/$templateKey/contract/draft",
            HttpMethod.PATCH,
            HttpEntity(body, jsonHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).contains("application/json")
        // template-detail.js onSaveSchema branches on success and reads warnings.
        assertThat(response.body).contains("\"success\":true")
        assertThat(response.body).contains("\"status\":\"draft\"")
    }

    private data class SeededStencil(
        val tenantKey: String,
        val catalogKey: String,
        val stencilKey: String,
    )

    private fun seedPublishedStencil(name: String): SeededStencil = withMediator {
        val tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name).execute()
        CreateStencilVersion(stencilId = stencilId).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
        SeededStencil(tenant.id.value, stencilId.catalogKey.value, stencilId.key.value)
    }

    private fun seedDraftStencil(name: String): SeededStencil = withMediator {
        val tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name).execute()
        CreateStencilVersion(stencilId = stencilId).execute()
        SeededStencil(tenant.id.value, stencilId.catalogKey.value, stencilId.key.value)
    }
}
