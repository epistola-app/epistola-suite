// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.suite.attributes.commands.AllowedValuesInUseException
import app.epistola.suite.attributes.commands.AttributeInUseException
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.InstalledResource
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * #1022: the checks that stop an attribute definition from being deleted, or its allowed values
 * narrowed, while variants still use it. A variant refers to `acme/brand` through the qualified key
 * `acme.brand` or the bare key `brand`, from a template in any of the tenant's catalogs — the same
 * resolution `AttributeValidation` applies when the variant is saved.
 */
class AttributeInUseTest : IntegrationTestBase() {

    @Autowired
    private lateinit var upgradeAnalyzer: CatalogUpgradeAnalyzer

    private val acme = CatalogKey.of("acme")
    private val brand = AttributeKey.of("brand")

    private fun brandIn(tenantKey: TenantKey) = AttributeId(brand, CatalogId(acme, TenantId(tenantKey)))

    /** Catalog `acme` defining `brand` (acme, globex), and nothing using it yet. */
    private fun setUp(): TenantKey = withMediator {
        val tenantKey = createTenant("Attribute in use").id
        CreateCatalog(tenantKey = tenantKey, id = acme, name = "Acme").execute()
        CreateAttributeDefinition(id = brandIn(tenantKey), displayName = "Brand", allowedValues = listOf("acme", "globex")).execute()
        tenantKey
    }

    /** A template in [catalog] with one variant carrying [attributes]. */
    private fun variantIn(tenantKey: TenantKey, catalog: CatalogKey, attributes: Map<String, String>) = withMediator {
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(catalog, TenantId(tenantKey)))
        CreateDocumentTemplate(id = templateId, name = "Uses brand").execute()
        CreateVariant(VariantId(TestIdHelpers.nextVariantId(), templateId), "Branded", null, attributes).execute()
    }

    private fun delete(tenantKey: TenantKey) = withMediator { DeleteAttributeDefinition(brandIn(tenantKey)).execute() }

    private fun narrowToGlobex(tenantKey: TenantKey) = withMediator {
        UpdateAttributeDefinition(brandIn(tenantKey), displayName = "Brand", allowedValues = listOf("globex")).execute()
    }

    @Test
    fun `delete is refused while a template in another catalog uses the qualified key`() {
        val tenantKey = setUp()
        variantIn(tenantKey, CatalogKey.DEFAULT, mapOf("acme.brand" to "acme"))

        assertThatThrownBy { delete(tenantKey) }.isInstanceOf(AttributeInUseException::class.java)
    }

    @Test
    fun `delete is refused while a template in the same catalog uses the qualified key`() {
        val tenantKey = setUp()
        variantIn(tenantKey, acme, mapOf("acme.brand" to "acme"))

        assertThatThrownBy { delete(tenantKey) }.isInstanceOf(AttributeInUseException::class.java)
    }

    @Test
    fun `delete is refused while a template in another catalog uses the bare key`() {
        val tenantKey = setUp()
        variantIn(tenantKey, CatalogKey.DEFAULT, mapOf("brand" to "acme"))

        assertThatThrownBy { delete(tenantKey) }.isInstanceOf(AttributeInUseException::class.java)
    }

    @Test
    fun `delete succeeds when no variant uses the attribute`() {
        val tenantKey = setUp()
        variantIn(tenantKey, CatalogKey.DEFAULT, emptyMap())

        assertThat(delete(tenantKey)).isTrue()
    }

    @Test
    fun `narrowing allowed values is refused while a template in another catalog uses a removed value`() {
        val tenantKey = setUp()
        variantIn(tenantKey, CatalogKey.DEFAULT, mapOf("acme.brand" to "acme"))

        assertThatThrownBy { narrowToGlobex(tenantKey) }
            .isInstanceOf(AllowedValuesInUseException::class.java)
            .hasMessageContaining("'acme'")
    }

    @Test
    fun `narrowing allowed values succeeds when only kept values are used`() {
        val tenantKey = setUp()
        variantIn(tenantKey, CatalogKey.DEFAULT, mapOf("acme.brand" to "globex"))

        assertThat(narrowToGlobex(tenantKey)?.allowedValues).containsExactly("globex")
    }

    @Test
    fun `a catalog upgrade that drops the attribute reports templates using the qualified key`() {
        val tenantKey = setUp()
        variantIn(tenantKey, CatalogKey.DEFAULT, mapOf("acme.brand" to "acme"))

        val conflicts = upgradeAnalyzer.findConflicts(tenantKey, acme, listOf(InstalledResource("attribute", "brand")))

        assertThat(conflicts).anyMatch { it.contains("brand") && it.contains("Uses brand") }
    }
}
