package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeListEntryHidden
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.variants.validateAttributes
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Variant attribute validation when the bound attribute uses a code list,
 * exercising the full path: definition → bound code list → entry existence
 * check against the DB.
 */
class AttributeValidationCodeListTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

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
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("my-locale"), catalogId),
                displayName = "Locale",
                codeListId = codeListId,
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("my-locale" to "en")) }
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
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("my-locale"), catalogId),
                displayName = "Locale",
                codeListId = codeListId,
            ).execute()

            assertThatThrownBy { validateAttributes(tenantId, mapOf("my-locale" to "zz")) }
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
            ).execute()

            UpdateCodeListEntryHidden(codeListId, "legacy", hidden = true).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("status"), catalogId),
                displayName = "Status",
                codeListId = codeListId,
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("status" to "legacy")) }
                .doesNotThrowAnyException()
            assertThatCode { validateAttributes(tenantId, mapOf("status" to "active")) }
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
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("brand" to "acme")) }
                .doesNotThrowAnyException()
            assertThatThrownBy { validateAttributes(tenantId, mapOf("brand" to "other")) }
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
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("notes" to "anything-goes")) }
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `unknown attribute key is rejected`() {
        val tenant = createTenant("ValT6")
        val tenantId = TenantId(tenant.id)

        withMediator {
            assertThatThrownBy { validateAttributes(tenantId, mapOf("not-defined" to "x")) }
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

        // Create a second authored catalog "shared" alongside default.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (id, tenant_key, name, type, created_at, updated_at)
                VALUES ('shared', :tenantKey, 'Shared', 'AUTHORED', NOW(), NOW())
                """,
            )
                .bind("tenantKey", tenant.id)
                .execute()
        }

        val sharedCatalogKey = app.epistola.suite.common.ids.CatalogKey.of("shared")
        val codeListId = CodeListId(
            CodeListKey.of("regions"),
            CatalogId(sharedCatalogKey, tenantId),
        )

        withMediator {
            CreateCodeList(
                id = codeListId,
                displayName = "Regions",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("eu", "Europe"), CodeListEntry("us", "United States")),
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("region"), defaultCatalog),
                displayName = "Region",
                codeListId = codeListId,
            ).execute()

            assertThatCode { validateAttributes(tenantId, mapOf("region" to "eu")) }
                .doesNotThrowAnyException()
            assertThatThrownBy { validateAttributes(tenantId, mapOf("region" to "zz")) }
                .isInstanceOf(ValidationException::class.java)
        }
    }
}
