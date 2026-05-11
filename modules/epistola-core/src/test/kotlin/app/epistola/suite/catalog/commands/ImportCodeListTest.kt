package app.epistola.suite.catalog.commands

import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Coverage for [ImportCodeList]: catalog-driven UPSERT of code lists,
 * `source_type = INLINE` baseline, and atomic replacement of entries on
 * repeat install.
 */
class ImportCodeListTest : IntegrationTestBase() {

    @Test
    fun `first import inserts code list and entries with INLINE source`() {
        val tenant = createTenant("ImportCL1")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.DEFAULT
        val codeListId = CodeListId(CodeListKey.of("colors"), CatalogId(catalogKey, tenantId))

        withMediator {
            val status = ImportCodeList(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = "colors",
                displayName = "Colors",
                description = "Brand palette",
                entries = listOf(
                    CodeListEntry("red", "Red", sortOrder = 1),
                    CodeListEntry("blue", "Blue", sortOrder = 2),
                ),
            ).execute()

            assertThat(status).isEqualTo(InstallStatus.INSTALLED)

            val stored = GetCodeList(codeListId).query()
            assertThat(stored).isNotNull()
            assertThat(stored!!.displayName).isEqualTo("Colors")
            assertThat(stored.description).isEqualTo("Brand palette")
            assertThat(stored.sourceType).isEqualTo(CodeListSource.INLINE)
            assertThat(stored.sourceUrl).isNull()

            val entries = ListCodeListEntries(codeListId).query()
            assertThat(entries).extracting("code").containsExactly("red", "blue")
        }
    }

    @Test
    fun `re-import replaces entries atomically and returns UPDATED`() {
        val tenant = createTenant("ImportCL2")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.DEFAULT
        val codeListId = CodeListId(CodeListKey.of("seasons"), CatalogId(catalogKey, tenantId))

        withMediator {
            ImportCodeList(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = "seasons",
                displayName = "Seasons (v1)",
                entries = listOf(
                    CodeListEntry("spring", "Spring"),
                    CodeListEntry("summer", "Summer"),
                ),
            ).execute()

            val secondStatus = ImportCodeList(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = "seasons",
                displayName = "Seasons (v2)",
                entries = listOf(
                    CodeListEntry("autumn", "Autumn"),
                    CodeListEntry("winter", "Winter"),
                ),
            ).execute()

            assertThat(secondStatus).isEqualTo(InstallStatus.UPDATED)

            val stored = GetCodeList(codeListId).query()
            assertThat(stored!!.displayName).isEqualTo("Seasons (v2)")

            val entries = ListCodeListEntries(codeListId).query()
            assertThat(entries).extracting("code").containsExactlyInAnyOrder("autumn", "winter")
        }
    }

    @Test
    fun `entries carrying sort_order and hidden flags round-trip`() {
        val tenant = createTenant("ImportCL3")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.DEFAULT
        val codeListId = CodeListId(CodeListKey.of("statuses"), CatalogId(catalogKey, tenantId))

        withMediator {
            ImportCodeList(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = "statuses",
                displayName = "Statuses",
                entries = listOf(
                    CodeListEntry("active", "Active", sortOrder = 1),
                    CodeListEntry("legacy", "Legacy", sortOrder = 99, hidden = true),
                ),
            ).execute()

            val entries = ListCodeListEntries(codeListId, includeHidden = true).query()
            val legacy = entries.single { it.code == "legacy" }
            assertThat(legacy.hidden).isTrue()
            assertThat(legacy.sortOrder).isEqualTo(99)
        }
    }
}
