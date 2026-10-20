// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.deployLatestRelease
import app.epistola.suite.testing.publishAndRelease
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * Trying a document on an environment's page: a deployed template, as the environment serves it,
 * made with its first example data, without calling the API.
 */
class EnvironmentTryDocumentHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the environment page offers its deployed templates and makes a PDF of one`() {
        val tenantKey = TenantKey.of("try-doc-${System.nanoTime()}")
        val (templateKey, version) = withMediator {
            CreateTenant(tenantKey, "Try Document").execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenantKey)))
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()).execute()
            UpdateVariantContent(variantId = variantId, templateModel = TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(variantId)
            val staging = EnvironmentId(EnvironmentKey.of("staging"), TenantId(tenantKey))
            CreateEnvironment(staging, "Staging").execute()
            templateId.key.value to mediator.deployLatestRelease(staging, templateId.catalogId)
        }
        val choice = "default@$version/$templateKey"

        val page = restTemplate.getForEntity("/tenants/${tenantKey.value}/environments/staging", String::class.java)
        assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(page.body).contains("data-testid=\"environment-try-form\"").contains("value=\"$choice\"").contains("Make a PDF")

        val pdf = restTemplate.getForEntity("/tenants/${tenantKey.value}/environments/staging/try?template=$choice", ByteArray::class.java)
        assertThat(pdf.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(pdf.headers.contentType).isEqualTo(MediaType.APPLICATION_PDF)
        assertThat(String(pdf.body!!.copyOfRange(0, 5))).isEqualTo("%PDF-")
    }
}
