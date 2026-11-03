// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeListEntryHidden
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.validateAttributes
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Variant attribute validation when the bound attribute uses a code list,
 * exercising the full path: definition → bound code list → entry existence
 * check against the DB.
 */
class AttributeValidationCodeListTest : IntegrationTestBase() {

    @Test
    fun `value accepted when present in bound code list`() {
        val tenant = createTenant("ValT")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val codeListId = CodeListId(CodeListKey.of("locales"), catalogId)

        withMediator {
            CreateCodeList(
                id = codeListId,
                displayName = "Locales",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("en", "English"), CodeListEntry("nl", "Dutch")),
                catalogVersion = null,
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("my-locale"), catalogId),
                displayName = "Locale",
                codeListId = codeListId,
                catalogVersion = null,
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("my-locale" to "en"), catalogVersion = null) }
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `value rejected when not present in bound code list`() {
        val tenant = createTenant("ValT2")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val codeListId = CodeListId(CodeListKey.of("locales"), catalogId)

        withMediator {
            CreateCodeList(
                id = codeListId,
                displayName = "Locales",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("en", "English")),
                catalogVersion = null,
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("my-locale"), catalogId),
                displayName = "Locale",
                codeListId = codeListId,
                catalogVersion = null,
            ).execute()

            assertThatThrownBy { validateAttributes(tenantId, mapOf("my-locale" to "zz"), catalogVersion = null) }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Not a member of code list")
        }
    }

    @Test
    fun `hidden code is still accepted by validation`() {
        val tenant = createTenant("ValT3")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val codeListId = CodeListId(CodeListKey.of("statuses"), catalogId)

        withMediator {
            CreateCodeList(
                id = codeListId,
                displayName = "Statuses",
                sourceType = CodeListSource.INLINE,
                entries = listOf(
                    CodeListEntry("active", "Active"),
                    CodeListEntry("legacy", "Legacy"),
                ),
                catalogVersion = null,
            ).execute()

            UpdateCodeListEntryHidden(codeListId, "legacy", hidden = true, catalogVersion = null).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("status"), catalogId),
                displayName = "Status",
                codeListId = codeListId,
                catalogVersion = null,
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("status" to "legacy"), catalogVersion = null) }
                .doesNotThrowAnyException()
            assertThatCode { validateAttributes(tenantId, mapOf("status" to "active"), catalogVersion = null) }
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `inline allowed values still work alongside code-list attributes`() {
        val tenant = createTenant("ValT4")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("brand"), catalogId),
                displayName = "Brand",
                allowedValues = listOf("acme", "rocket"),
                catalogVersion = null,
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("brand" to "acme"), catalogVersion = null) }
                .doesNotThrowAnyException()
            assertThatThrownBy { validateAttributes(tenantId, mapOf("brand" to "other"), catalogVersion = null) }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Allowed values")
        }
    }

    @Test
    fun `free-format attribute accepts any value`() {
        val tenant = createTenant("ValT5")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("notes"), catalogId),
                displayName = "Notes",
                catalogVersion = null,
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("notes" to "anything-goes"), catalogVersion = null) }
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `unknown attribute key is rejected`() {
        val tenant = createTenant("ValT6")
        val tenantId = TenantId(tenant.id)

        withMediator {
            assertThatThrownBy { validateAttributes(tenantId, mapOf("not-defined" to "x"), catalogVersion = null) }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Unknown attribute")
        }
    }

    @Test
    fun `attribute can bind to code list across catalogs within the same tenant`() {
        // This is the cross-catalog binding scenario: attribute in `default`
        // catalog references a code list in another catalog of the same tenant.
        val tenant = createTenant("ValT7")
        val tenantId = TenantId(tenant.id)
        val defaultCatalog = CatalogId.default(tenantId)

        // A second authored catalog "shared" alongside default.
        withMediator { CreateCatalog(tenant.id, app.epistola.suite.common.ids.CatalogKey.of("shared"), "Shared").execute() }

        val sharedCatalogKey = app.epistola.suite.common.ids.CatalogKey.of("shared")
        val codeListId = CodeListId(
            CodeListKey.of("regions"),
            testCatalog(tenantId, sharedCatalogKey),
        )

        withMediator {
            CreateCodeList(
                id = codeListId,
                displayName = "Regions",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("eu", "Europe"), CodeListEntry("us", "United States")),
                catalogVersion = null,
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("region"), defaultCatalog),
                displayName = "Region",
                codeListId = codeListId,
                catalogVersion = null,
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("region" to "eu"), catalogVersion = null) }
                .doesNotThrowAnyException()
            assertThatThrownBy { validateAttributes(tenantId, mapOf("region" to "zz"), catalogVersion = null) }
                .isInstanceOf(ValidationException::class.java)
        }
    }
}
