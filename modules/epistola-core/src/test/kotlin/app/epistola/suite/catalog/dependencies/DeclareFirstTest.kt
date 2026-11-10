// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclareCatalogDependency
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Using another catalog's resource is a deliberate step on every surface alike: a write that would
 * make a version use a catalog it does not declare is refused (`DraftReferenceGuard`), naming the
 * catalog to declare; declared first ([DeclareCatalogDependency]), the same write goes through.
 */
class DeclareFirstTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")
    private val invoice = TemplateKey.of("invoice")

    private fun catalogs(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            listOf(letters, shared).forEach { CreateCatalog(tenant, it, it.value).execute() }
            CreateDocumentTemplate(TemplateId(invoice, testCatalog(tenant, letters)), "Invoice", catalogVersion = null).execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(tenant, shared)), "Shared brand", catalogVersion = null).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("audience"), testCatalog(tenant, shared)), "Audience", allowedValues = listOf("citizen"), catalogVersion = null).execute()
            CreateCodeList(CodeListId(CodeListKey.of("languages"), testCatalog(tenant, shared)), "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands")), catalogVersion = null).execute()
        }
        return tenant
    }

    private fun declared(tenant: TenantKey) = withMediator { ListCatalogDependencies(testCatalog(tenant, letters), catalogVersion = null).query() }.map { it.catalogKey }

    private fun declareShared(tenant: TenantKey) = withMediator { DeclareCatalogDependency(testCatalog(tenant, letters), testCatalog(tenant, shared), catalogVersion = null).execute() }

    private fun refusedAsUndeclared(write: () -> Unit) {
        assertThatThrownBy { write() }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining(shared.value)
            .extracting { (it as ValidationException).code }
            .isEqualTo(ValidationCode.CATALOG_NOT_DECLARED)
    }

    private fun setTheme(tenant: TenantKey) = withMediator {
        UpdateDocumentTemplate(TemplateId(invoice, testCatalog(tenant, letters)), themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(tenant, shared), catalogVersion = null).execute()
    }

    @Test
    fun `a template's theme from a catalog it does not declare is refused, and taken once it is declared`() {
        val tenant = catalogs("declare-first-theme")

        refusedAsUndeclared { setTheme(tenant) }
        assertThat(declared(tenant)).doesNotContain(shared.value)

        declareShared(tenant)

        setTheme(tenant)
    }

    @Test
    fun `a variant carrying an attribute of a catalog it does not declare is refused`() {
        val tenant = catalogs("declare-first-attribute")
        fun create(key: String) = withMediator {
            CreateVariant(VariantId(VariantKey.of(key), TemplateId(invoice, testCatalog(tenant, letters))), "Citizens", null, mapOf("shared.audience" to "citizen"), catalogVersion = null).execute()
        }

        refusedAsUndeclared { create("citizens") }

        declareShared(tenant)

        create("citizens")
    }

    @Test
    fun `an attribute bound to a code list of a catalog it does not declare is refused`() {
        val tenant = catalogs("declare-first-code-list")
        fun create() = withMediator {
            CreateAttributeDefinition(AttributeId(AttributeKey.of("language"), testCatalog(tenant, letters)), "Language", codeListId = CodeListId(CodeListKey.of("languages"), testCatalog(tenant, shared)), catalogVersion = null).execute()
        }

        refusedAsUndeclared { create() }

        declareShared(tenant)

        create()
    }

    @Test
    fun `a default theme from a catalog it does not declare is refused`() {
        val tenant = catalogs("declare-first-default-theme")
        fun set() = withMediator {
            SetCatalogDefaultTheme(testCatalog(tenant, letters), CatalogDefaultTheme(ThemeKey.of("brand"), testCatalog(tenant, shared)), catalogVersion = null).execute()
        }

        refusedAsUndeclared { set() }
        assertThat(declared(tenant)).doesNotContain(shared.value)

        declareShared(tenant)

        set()
    }
}
