// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
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
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

/**
 * ADR 0034, phase 4: within a tenant a catalog is unique by namespace and key, so two catalogs called
 * `letters`, from two namespaces, live side by side. Work names the namespace it means
 * ([CatalogNames]); a key that names both is refused rather than taken to mean either.
 */
class SideBySideCatalogsTest : IntegrationTestBase() {

    private val acme = NamespaceName("gemeente-x.nl", "acme")
    private val bureau = NamespaceName("gemeente-x.nl", "bureau")
    private val letters = CatalogKey.of("letters")
    private val invoice = TemplateKey.of("invoice")

    private fun tenant(name: String, vararg namespaces: NamespaceName): TenantKey = withMediator {
        CreateTenant(TenantKey.of("sidebyside-${TestTenantCounter.next("sidebyside")}"), name, namespaces.toList()).execute().id
    }

    /** `letters` in [namespace] of [tenant], with an invoice titled [title], released as 1.0.0. */
    private fun authored(tenant: TenantKey, namespace: String, title: String) = withMediator {
        CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
        CatalogNames.within(letters, namespace) {
            val variant = VariantId(VariantKey.INITIAL, TemplateId(invoice, CatalogId(letters, TenantId(tenant))))
            CreateDocumentTemplate(variant.templateId, title).execute().withRequiredDataExample()
            UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = title)).execute()
            mediator.publishAndRelease(variant)
        }
    }

    @Test
    fun `two letters live side by side, each created, edited, released and rendered in its namespace`() {
        val tenant = tenant("Side by side", acme, bureau)
        assertThat(authored(tenant, "acme", "Acme invoice")).isEqualTo("1.0.0")
        assertThat(authored(tenant, "bureau", "Bureau invoice")).isEqualTo("1.0.0")

        withMediator {
            assertThat(ListCatalogs(tenant).query().filter { it.id == letters }.map { it.namespace }).containsExactlyInAnyOrder("acme", "bureau")
            for ((namespace, title) in listOf("acme" to "Acme invoice", "bureau" to "Bureau invoice")) {
                CatalogNames.within(letters, namespace) {
                    assertThat(GetCatalog(tenant, letters).query()!!.namespace).isEqualTo(namespace)
                    assertThat(GetDocumentTemplate(TemplateId(invoice, CatalogId(letters, TenantId(tenant)))).query()!!.name).isEqualTo(title)
                    assertThat(ListCatalogReleaseSummaries(tenant, letters).query().map { it.version }).containsExactly("1.0.0")
                    val pdf = PreviewDocument(tenant, letters, invoice, JsonNodeFactory.instance.objectNode(), releaseVersion = "1.0.0").query()
                    assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")
                }
            }
        }
    }

    @Test
    fun `a key that names both is refused, saying which`() {
        val tenant = tenant("Side by side ambiguous", acme, bureau)
        withMediator {
            CreateCatalog(tenant, letters, "Acme letters", namespace = "acme").execute()
            CreateCatalog(tenant, letters, "Bureau letters", namespace = "bureau").execute()

            assertThatThrownBy { GetCatalog(tenant, letters).query() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.CATALOG_KEY_AMBIGUOUS)
                    assertThat(it.message).isEqualTo("The catalog key letters names several catalogs of this tenant (acme/letters, bureau/letters): name its namespace.")
                }
            assertThatThrownBy { CreateDocumentTemplate(TemplateId(invoice, CatalogId(letters, TenantId(tenant))), "Which?").execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo(ValidationCode.CATALOG_KEY_AMBIGUOUS) }
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
            val archive = CatalogNames.within(letters, "acme") { ExportCatalogZip(author, letters, "1.0.0").execute().zipBytes }
            ImportCatalogZip(receiver, archive).execute()

            assertThat(ListCatalogs(receiver).query().filter { it.id == letters }.map { it.namespace }).containsExactlyInAnyOrder("acme", "bureau")
            CatalogNames.within(letters, "acme") {
                assertThat(GetDocumentTemplate(TemplateId(invoice, CatalogId(letters, TenantId(receiver)))).query()!!.name).isEqualTo("Acme invoice")
            }
            CatalogNames.within(letters, "bureau") {
                assertThat(GetDocumentTemplate(TemplateId(invoice, CatalogId(letters, TenantId(receiver)))).query()!!.name).isEqualTo("Bureau invoice")
            }
        }
    }
}
