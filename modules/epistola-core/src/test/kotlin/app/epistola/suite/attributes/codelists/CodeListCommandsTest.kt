package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.commands.CodeListInUseException
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.DeleteCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeListEntryHidden
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.CodeListEntryExists
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
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

class CodeListCommandsTest : IntegrationTestBase() {

    @Test
    fun `create inline code list with entries`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("audience-segments"), CatalogId.default(tenantId))

        withMediator {
            val created = CreateCodeList(
                id = id,
                displayName = "Audience Segments",
                sourceType = CodeListSource.INLINE,
                entries = listOf(
                    CodeListEntry("retail", "Retail Customers", sortOrder = 1),
                    CodeListEntry("smb", "Small & Medium Business", sortOrder = 2),
                ),
            ).execute()

            assertThat(created.slug.value).isEqualTo("audience-segments")
            assertThat(created.displayName).isEqualTo("Audience Segments")
            assertThat(created.sourceType).isEqualTo(CodeListSource.INLINE)

            val entries = ListCodeListEntries(id).query()
            assertThat(entries).hasSize(2)
            assertThat(entries.map { it.code }).containsExactly("retail", "smb")
        }
    }

    @Test
    fun `inline code list rejects empty entries`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("empty-list"), CatalogId.default(tenantId))

        assertThatThrownBy {
            CreateCodeList(
                id = id,
                displayName = "Empty",
                sourceType = CodeListSource.INLINE,
                entries = emptyList(),
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("at least one entry")
    }

    @Test
    fun `inline code list rejects duplicate entry codes`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("dup-list"), CatalogId.default(tenantId))

        assertThatThrownBy {
            CreateCodeList(
                id = id,
                displayName = "Dup",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("a", "A"), CodeListEntry("a", "Aa")),
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("unique")
    }

    @Test
    fun `URL code list creates with empty entries and source url`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("regions"), CatalogId.default(tenantId))

        withMediator {
            val created = CreateCodeList(
                id = id,
                displayName = "Regions",
                sourceType = CodeListSource.URL,
                sourceUrl = "https://example.com/regions.json",
            ).execute()

            assertThat(created.sourceType).isEqualTo(CodeListSource.URL)
            assertThat(created.sourceUrl).isEqualTo("https://example.com/regions.json")
            assertThat(ListCodeListEntries(id).query()).isEmpty()
        }
    }

    @Test
    fun `URL code list requires source url`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("no-url"), CatalogId.default(tenantId))

        assertThatThrownBy {
            CreateCodeList(
                id = id,
                displayName = "No URL",
                sourceType = CodeListSource.URL,
                sourceUrl = null,
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("Source URL is required")
    }

    @Test
    fun `update inline code list replaces entries`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("colors"), CatalogId.default(tenantId))

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "Colors",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("red", "Red"), CodeListEntry("blue", "Blue")),
            ).execute()

            UpdateCodeList(
                id = id,
                displayName = "Colors v2",
                entries = listOf(
                    CodeListEntry("red", "Red"),
                    CodeListEntry("green", "Green"),
                ),
            ).execute()

            val entries = ListCodeListEntries(id).query()
            assertThat(entries.map { it.code }).containsExactlyInAnyOrder("red", "green")
        }
    }

    @Test
    fun `delete code list cascades entries`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("transient"), CatalogId.default(tenantId))

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "Transient",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("x", "X")),
            ).execute()

            assertThat(DeleteCodeList(id).execute()).isTrue()
            assertThat(GetCodeList(id).query()).isNull()
            assertThat(ListCodeListEntries(id).query()).isEmpty()
        }
    }

    @Test
    fun `delete code list refuses when attribute is bound`() {
        val tenant = createTenant("Test Tenant")
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
                id = AttributeId(AttributeKey.of("locale"), catalogId),
                displayName = "Locale",
                codeListCatalogKey = catalogId.key,
                codeListSlug = codeListId.key,
            ).execute()

            assertThatThrownBy { DeleteCodeList(codeListId).execute() }
                .isInstanceOf(CodeListInUseException::class.java)
                .hasMessageContaining("locale")
        }
    }

    @Test
    fun `list code lists is tenant scoped`() {
        val a = createTenant("Tenant A")
        val b = createTenant("Tenant B")
        val idA = CodeListId(CodeListKey.of("list-a"), CatalogId.default(TenantId(a.id)))
        val idB = CodeListId(CodeListKey.of("list-b"), CatalogId.default(TenantId(b.id)))

        withMediator {
            CreateCodeList(
                id = idA,
                displayName = "A",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("x", "X")),
            ).execute()
            CreateCodeList(
                id = idB,
                displayName = "B",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("y", "Y")),
            ).execute()

            val resultA = ListCodeLists(TenantId(a.id)).query()
            val resultB = ListCodeLists(TenantId(b.id)).query()
            assertThat(resultA).extracting<String> { it.slug.value }.containsExactly("list-a")
            assertThat(resultB).extracting<String> { it.slug.value }.containsExactly("list-b")
        }
    }

    @Test
    fun `hidden entry remains valid for variant validation`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val id = CodeListId(CodeListKey.of("statuses"), catalogId)

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "Statuses",
                sourceType = CodeListSource.INLINE,
                entries = listOf(
                    CodeListEntry("active", "Active"),
                    CodeListEntry("legacy", "Legacy"),
                ),
            ).execute()

            // Hide the legacy entry.
            UpdateCodeListEntryHidden(id, "legacy", hidden = true).execute()

            val visible = ListCodeListEntries(id, includeHidden = false).query()
            assertThat(visible.map { it.code }).containsExactly("active")

            val all = ListCodeListEntries(id, includeHidden = true).query()
            assertThat(all.map { it.code }).containsExactlyInAnyOrder("active", "legacy")

            // Validation accepts both visible and hidden codes.
            assertThat(
                CodeListEntryExists(tenant.id, catalogId.key, id.key, "active").query(),
            ).isTrue()
            assertThat(
                CodeListEntryExists(tenant.id, catalogId.key, id.key, "legacy").query(),
            ).isTrue()
            assertThat(
                CodeListEntryExists(tenant.id, catalogId.key, id.key, "unknown").query(),
            ).isFalse()
        }
    }
}
