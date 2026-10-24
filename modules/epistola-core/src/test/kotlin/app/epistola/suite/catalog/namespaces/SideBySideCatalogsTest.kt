// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.queries.FindCatalogsByKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestTenantCounter
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

/**
 * ADR 0034, section 9: within a tenant a catalog is unique by namespace and key, and named by both
 * ([CatalogId]), so two catalogs called `letters`, from two namespaces, live side by side, with no
 * switch to turn on. A key alone names a catalog only while one has it ([FindCatalogsByKey]).
 */
class SideBySideCatalogsTest : IntegrationTestBase() {

    private val acme = NamespaceName("gemeente-x.nl", "acme")
    private val bureau = NamespaceName("gemeente-x.nl", "bureau")
    private val letters = CatalogKey.of("letters")
    private val invoice = TemplateKey.of("invoice")

    private fun tenant(name: String, vararg namespaces: NamespaceName): TenantKey = withMediator {
        CreateTenant(TenantKey.of("sidebyside-${TestTenantCounter.next("sidebyside")}"), name, namespaces.toList()).execute().id
    }

    private fun letters(tenant: TenantKey, namespace: String) = CatalogId(namespace, letters, TenantId(tenant))

    /** `letters` in [namespace] of [tenant], with an invoice titled [title], released as 1.0.0. */
    private fun authored(tenant: TenantKey, namespace: String, title: String) = withMediator {
        CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
        val variant = VariantId(VariantKey.INITIAL, TemplateId(invoice, letters(tenant, namespace)))
        CreateDocumentTemplate(variant.templateId, title).execute().withRequiredDataExample()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = title)).execute()
        mediator.publishAndRelease(variant)
    }

    @Test
    fun `two letters live side by side, each created, edited, released and rendered at its address`() {
        val tenant = tenant("Side by side", acme, bureau)
        assertThat(authored(tenant, "acme", "Acme invoice")).isEqualTo("1.0.0")
        assertThat(authored(tenant, "bureau", "Bureau invoice")).isEqualTo("1.0.0")

        withMediator {
            assertThat(ListCatalogs(tenant).query().filter { it.id == letters }.map { it.namespace }).containsExactlyInAnyOrder("acme", "bureau")
            for ((namespace, title) in listOf("acme" to "Acme invoice", "bureau" to "Bureau invoice")) {
                val catalog = letters(tenant, namespace)
                assertThat(GetCatalog(catalog).query()!!.namespace).isEqualTo(namespace)
                assertThat(GetDocumentTemplate(TemplateId(invoice, catalog)).query()!!.name).isEqualTo(title)
                assertThat(ListCatalogReleaseSummaries(catalog).query().map { it.version }).containsExactly("1.0.0")
                val pdf = PreviewDocument(catalog, invoice, JsonNodeFactory.instance.objectNode(), releaseVersion = "1.0.0").query()
                assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")
            }
        }
    }

    @Test
    fun `an address names one catalog, and a key alone none while two have it`() {
        val tenant = tenant("Side by side addresses", acme, bureau)
        withMediator {
            CreateCatalog(tenant, letters, "Acme letters", namespace = "acme").execute()
            assertThat(FindCatalogsByKey(tenant, letters).query().only).isEqualTo(letters(tenant, "acme"))
            CreateCatalog(tenant, letters, "Bureau letters", namespace = "bureau").execute()

            assertThat(GetCatalog(letters(tenant, "acme")).query()!!.name).isEqualTo("Acme letters")
            assertThat(GetCatalog(letters(tenant, "bureau")).query()!!.name).isEqualTo("Bureau letters")
            assertThat(FindCatalogsByKey(tenant, letters).query().catalogs).containsExactly(letters(tenant, "acme"), letters(tenant, "bureau"))
            assertThat(FindCatalogsByKey(tenant, letters).query().only).isNull()
            // A namespace that holds no catalog with the key names none.
            assertThat(GetCatalog(letters(tenant, "elsewhere")).query()).isNull()
            // The same namespace twice is still one catalog.
            assertThatThrownBy { CreateCatalog(tenant, letters, "Again", namespace = "acme").execute() }.hasMessageContaining("letters")
        }
    }

    @Test
    fun `a received letters from another namespace is installed beside the tenant's own`() {
        val author = tenant("Side by side author", acme)
        authored(author, "acme", "Acme invoice")
        val receiver = tenant("Side by side receiver", bureau)
        authored(receiver, "bureau", "Bureau invoice")

        withMediator {
            val archive = ExportCatalogZip(letters(author, "acme"), "1.0.0").execute().zipBytes
            ImportCatalogZip(receiver, archive).execute()

            assertThat(ListCatalogs(receiver).query().filter { it.id == letters }.map { it.namespace }).containsExactlyInAnyOrder("acme", "bureau")
            assertThat(GetDocumentTemplate(TemplateId(invoice, letters(receiver, "acme"))).query()!!.name).isEqualTo("Acme invoice")
            assertThat(GetDocumentTemplate(TemplateId(invoice, letters(receiver, "bureau"))).query()!!.name).isEqualTo("Bureau invoice")
        }
    }
}
