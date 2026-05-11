package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Coverage of the attribute → code list binding lifecycle. Focuses on:
 *
 *  - the XOR validation between inline `allowedValues` and a code-list binding,
 *  - the both-or-neither pairing of `codeListCatalogKey` and `codeListSlug`,
 *  - the FK enforcement (binding to a non-existent code list fails),
 *  - and the model round-trip so the binding survives a `Get` query.
 */
class AttributeCodeListBindingTest : IntegrationTestBase() {

    @Test
    fun `create attribute bound to a code list persists the binding`() {
        val tenant = createTenant("Bind1")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val codeListId = CodeListId(CodeListKey.of("languages"), catalogId)

        withMediator {
            CreateCodeList(
                id = codeListId,
                displayName = "Languages",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("en", "English")),
            ).execute()

            val attr = CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), catalogId),
                displayName = "Language",
                codeListId = codeListId,
            ).execute()

            assertThat(attr.codeListId).isEqualTo(codeListId)
            assertThat(attr.allowedValues).isEmpty()

            val fetched = GetAttributeDefinition(AttributeId(attr.id, catalogId)).query()
            assertThat(fetched).isNotNull()
            assertThat(fetched!!.codeListId?.key?.value).isEqualTo("languages")
        }
    }

    @Test
    fun `create attribute rejects mixing inline values and code-list binding`() {
        val tenant = createTenant("Bind2")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)

        assertThatThrownBy {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("mixed"), catalogId),
                displayName = "Mixed",
                allowedValues = listOf("a", "b"),
                codeListId = CodeListId(CodeListKey.of("anything"), catalogId),
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("cannot have both")
    }

    @Test
    fun `create attribute rejects code-list binding from a different tenant`() {
        val tenant = createTenant("Bind3")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)

        val otherTenant = createTenant("Bind3Other")
        val otherCatalogId = CatalogId.default(TenantId(otherTenant.id))

        // The new API forces callers to pass a typed `CodeListId`, so the
        // "half-specified binding" failure mode that the old two-key API had
        // is impossible by construction. The remaining cross-cutting check
        // we can drive from the test is the same-tenant invariant: an
        // attribute cannot bind to a code list owned by a different tenant.
        assertThatThrownBy {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("cross"), catalogId),
                displayName = "Cross-tenant",
                codeListId = CodeListId(CodeListKey.of("foreign"), otherCatalogId),
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("same tenant")
    }

    @Test
    fun `create attribute fails on FK violation when bound code list does not exist`() {
        val tenant = createTenant("Bind4")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)

        withMediator {
            assertThatThrownBy {
                CreateAttributeDefinition(
                    id = AttributeId(AttributeKey.of("region"), catalogId),
                    displayName = "Region",
                    codeListId = CodeListId(CodeListKey.of("does-not-exist"), catalogId),
                ).execute()
            }.hasMessageContaining("foreign key")
        }
    }

    @Test
    fun `update attribute can switch from inline values to code-list binding`() {
        val tenant = createTenant("Bind5")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val codeListId = CodeListId(CodeListKey.of("colors"), catalogId)

        withMediator {
            CreateCodeList(
                id = codeListId,
                displayName = "Colors",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("red", "Red"), CodeListEntry("blue", "Blue")),
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("color"), catalogId),
                displayName = "Color",
                allowedValues = listOf("red", "blue"),
            ).execute()

            val updated = UpdateAttributeDefinition(
                id = AttributeId(AttributeKey.of("color"), catalogId),
                displayName = "Color",
                codeListId = codeListId,
            ).execute()

            assertThat(updated?.codeListId).isEqualTo(codeListId)
            assertThat(updated?.allowedValues).isEmpty()
        }
    }

    @Test
    fun `update attribute can clear a code-list binding back to free format`() {
        val tenant = createTenant("Bind6")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val codeListId = CodeListId(CodeListKey.of("statuses"), catalogId)

        withMediator {
            CreateCodeList(
                id = codeListId,
                displayName = "Statuses",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("on", "On"), CodeListEntry("off", "Off")),
            ).execute()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("status"), catalogId),
                displayName = "Status",
                codeListId = codeListId,
            ).execute()

            val updated = UpdateAttributeDefinition(
                id = AttributeId(AttributeKey.of("status"), catalogId),
                displayName = "Status",
            ).execute()

            assertThat(updated?.codeListId).isNull()
            assertThat(updated?.allowedValues).isEmpty()
        }
    }
}
