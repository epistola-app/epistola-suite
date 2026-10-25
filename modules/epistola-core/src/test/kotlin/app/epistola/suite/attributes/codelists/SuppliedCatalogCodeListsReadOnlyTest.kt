// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.DeleteCodeList
import app.epistola.suite.attributes.codelists.commands.RefreshCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeListEntryHidden
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A catalog the suite supplies (a `classpath:` source, ADR 0032) is read-only: the
 * `requireCatalogEditable` helper refuses anything that would change one. This test pins that for
 * every code-list mutating command, because the helper is shared domain machinery and a single
 * missed call site would silently allow edits.
 */
class SuppliedCatalogCodeListsReadOnlyTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var codeLists: CodeListStore

    @Test
    fun `CreateCodeList is rejected on a catalog the suite supplies`() {
        val (tenantId, suppliedCatalog) = suppliedFixture()
        withMediator {
            assertThatThrownBy {
                CreateCodeList(
                    id = CodeListId(CodeListKey.of("any-list"), testCatalog(tenantId, suppliedCatalog)),
                    displayName = "Any list",
                    sourceType = CodeListSource.INLINE,
                    entries = listOf(CodeListEntry("x", "X")),
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `UpdateCodeList is rejected on a catalog the suite supplies`() {
        val (tenantId, suppliedCatalog) = suppliedFixture()
        // Inject a row directly — we can't use CreateCodeList for setup since
        // the catalog is read-only. This simulates a list its install put in place.
        seedCodeList(tenantId, suppliedCatalog, slug = "regions", display = "Regions")

        withMediator {
            assertThatThrownBy {
                UpdateCodeList(
                    id = CodeListId(CodeListKey.of("regions"), testCatalog(tenantId, suppliedCatalog)),
                    displayName = "Renamed",
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `DeleteCodeList is rejected on a catalog the suite supplies`() {
        val (tenantId, suppliedCatalog) = suppliedFixture()
        seedCodeList(tenantId, suppliedCatalog, slug = "regions", display = "Regions")

        withMediator {
            assertThatThrownBy {
                DeleteCodeList(
                    id = CodeListId(CodeListKey.of("regions"), testCatalog(tenantId, suppliedCatalog)),
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `RefreshCodeList is rejected on a catalog the suite supplies`() {
        val (tenantId, suppliedCatalog) = suppliedFixture()
        seedCodeList(tenantId, suppliedCatalog, slug = "regions", display = "Regions", sourceUrl = "https://example.com/r.json")

        withMediator {
            assertThatThrownBy {
                RefreshCodeList(
                    id = CodeListId(CodeListKey.of("regions"), testCatalog(tenantId, suppliedCatalog)),
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `UpdateCodeListEntryHidden is rejected on a catalog the suite supplies`() {
        val (tenantId, suppliedCatalog) = suppliedFixture()
        seedCodeList(tenantId, suppliedCatalog, slug = "regions", display = "Regions")
        seedEntry(tenantId, suppliedCatalog, slug = "regions", code = "eu", label = "Europe")

        withMediator {
            assertThatThrownBy {
                UpdateCodeListEntryHidden(
                    codeListId = CodeListId(CodeListKey.of("regions"), testCatalog(tenantId, suppliedCatalog)),
                    code = "eu",
                    hidden = true,
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a fresh tenant and a catalog the suite supplies inside it. We do the
     * insert via JDBI directly because the regular `RegisterCatalog` command
     * needs a reachable catalog manifest, which we don't want to set up here —
     * the test is about the read-only check, not the install flow.
     */
    private fun suppliedFixture(): Pair<TenantId, CatalogKey> {
        val tenant = createTenant("RO Test")
        val tenantId = TenantId(tenant.id)
        val suppliedCatalog = CatalogKey.of("remote-feed")
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, source_url, created_at, updated_at)
                VALUES (:id, :tenantKey, 'exchange.epistola.app', :tenantKey, :name, :sourceUrl, NOW(), NOW())
                """,
            )
                .bind("id", suppliedCatalog)
                .bind("tenantKey", tenant.id)
                .bind("name", "Remote Feed")
                .bind("sourceUrl", "classpath:epistola/catalogs/remote-feed/catalog.json")
                .execute()
        }
        return tenantId to suppliedCatalog
    }

    /**
     * Seeded through the store rather than a command, which is the exception the seeding rule allows:
     * the commands refuse to write into a catalog the suite supplies, and that refusal is the
     * behaviour under test. The store writes what a command would, without the refusal.
     */
    private fun seedCodeList(
        tenantId: TenantId,
        catalog: CatalogKey,
        slug: String,
        display: String,
        sourceUrl: String? = null,
    ) {
        val source = sourceUrl?.let { CodeListSourceSettings(CodeListSource.URL, it, app.epistola.suite.catalog.AuthType.NONE, null) }
        jdbi.useTransaction<Exception> { handle ->
            codeLists.create(handle, CodeListId(CodeListKey.of(slug), testCatalog(tenantId.key, catalog)), CodeListFields(display, null, emptyList(), source), null)
        }
    }

    private fun seedEntry(tenantId: TenantId, catalog: CatalogKey, slug: String, code: String, label: String) {
        val id = CodeListId(CodeListKey.of(slug), testCatalog(tenantId.key, catalog))
        jdbi.useTransaction<Exception> { handle ->
            val fields = requireNotNull(codeLists.fields(handle, id))
            codeLists.save(handle, id, fields.copy(entries = fields.entries + CodeListEntry(code, label)), null)
        }
    }
}
