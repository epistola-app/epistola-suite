// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The demo invoice really does demonstrate schema defaults: its contract declares a default for the
 * vendor's country and the (required) tax rate, and one example leaves both out. Previewing that
 * example only works because generation falls back to the defaults — without them the required
 * `taxRate` fails validation. It fails if someone "completes" the example, which would leave the demo
 * with nothing that relies on a default.
 */
class DemoInvoiceSchemaDefaultsIntegrationTest : IntegrationTestBase() {
    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"
    private val demoCatalog = CatalogKey.of("epistola-demo")
    private val invoice = TemplateKey.of("demo-invoice")

    @Test
    fun `the defaults example leaves out defaulted fields and still previews`() {
        val tenant = createTenant("Demo Invoice Defaults")
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = demoUrl).execute() }

        val contract = withMediator {
            GetLatestContractVersion(TemplateId(invoice, CatalogId(demoCatalog, TenantId(tenant.id)))).query()
        }!!
        val properties = contract.dataModel!!.get("properties")
        assertThat(properties.get("taxRate").get("default").asDouble()).isEqualTo(0.21)
        assertThat(properties.get("vendor").get("properties").get("country").get("default").asString())
            .isEqualTo("Netherlands")
        assertThat(contract.dataModel!!.get("required").iterator().asSequence().map { it.asString() }.toList())
            .contains("taxRate")

        val example = contract.dataExamples.single { it.name == "Domestic Invoice - Schema Defaults" }
        assertThat(example.data.has("taxRate")).isFalse()
        assertThat(example.data.get("vendor").has("country")).isFalse()

        val pdf = withMediator { preview(tenant.id, example.data) }
        assertThat(pdf).isNotEmpty()
    }

    private fun preview(tenantKey: TenantKey, data: tools.jackson.databind.node.ObjectNode) = PreviewDocument(
        tenantId = tenantKey,
        catalogKey = demoCatalog,
        templateId = invoice,
        data = data,
    ).query()
}
