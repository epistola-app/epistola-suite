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
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * SUBSCRIBED catalogs are read-only by design — the `requireCatalogEditable`
 * helper short-circuits anything that would mutate one. This test pins that
 * contract for every code-list mutating command, because the helper is shared
 * domain machinery and a single missed call site would silently allow edits.
 */
class SubscribedCodeListsReadOnlyTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `CreateCodeList is rejected on a SUBSCRIBED catalog`() {
        val (tenantId, subscribedCatalog) = subscribedFixture()
        withMediator {
            assertThatThrownBy {
                CreateCodeList(
                    id = CodeListId(CodeListKey.of("any-list"), CatalogId(subscribedCatalog, tenantId)),
                    displayName = "Any list",
                    sourceType = CodeListSource.INLINE,
                    entries = listOf(CodeListEntry("x", "X")),
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `UpdateCodeList is rejected on a SUBSCRIBED catalog`() {
        val (tenantId, subscribedCatalog) = subscribedFixture()
        // Inject a row directly — we can't use CreateCodeList for setup since
        // the catalog is SUBSCRIBED. This simulates a list that landed via a
        // future remote-catalog import.
        seedCodeList(tenantId, subscribedCatalog, slug = "regions", display = "Regions")

        withMediator {
            assertThatThrownBy {
                UpdateCodeList(
                    id = CodeListId(CodeListKey.of("regions"), CatalogId(subscribedCatalog, tenantId)),
                    displayName = "Renamed",
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `DeleteCodeList is rejected on a SUBSCRIBED catalog`() {
        val (tenantId, subscribedCatalog) = subscribedFixture()
        seedCodeList(tenantId, subscribedCatalog, slug = "regions", display = "Regions")

        withMediator {
            assertThatThrownBy {
                DeleteCodeList(
                    id = CodeListId(CodeListKey.of("regions"), CatalogId(subscribedCatalog, tenantId)),
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `RefreshCodeList is rejected on a SUBSCRIBED catalog`() {
        val (tenantId, subscribedCatalog) = subscribedFixture()
        seedCodeList(tenantId, subscribedCatalog, slug = "regions", display = "Regions", sourceUrl = "https://example.com/r.json")

        withMediator {
            assertThatThrownBy {
                RefreshCodeList(
                    id = CodeListId(CodeListKey.of("regions"), CatalogId(subscribedCatalog, tenantId)),
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `UpdateCodeListEntryHidden is rejected on a SUBSCRIBED catalog`() {
        val (tenantId, subscribedCatalog) = subscribedFixture()
        seedCodeList(tenantId, subscribedCatalog, slug = "regions", display = "Regions")
        seedEntry(tenantId, subscribedCatalog, slug = "regions", code = "eu", label = "Europe")

        withMediator {
            assertThatThrownBy {
                UpdateCodeListEntryHidden(
                    codeListId = CodeListId(CodeListKey.of("regions"), CatalogId(subscribedCatalog, tenantId)),
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
     * Creates a fresh tenant and a SUBSCRIBED catalog inside it. We do the
     * insert via JDBI directly because the regular `RegisterCatalog` command
     * needs a reachable catalog manifest, which we don't want to set up here —
     * the test is about the read-only check, not the subscription flow.
     */
    private fun subscribedFixture(): Pair<TenantId, CatalogKey> {
        val tenant = createTenant("RO Test")
        val tenantId = TenantId(tenant.id)
        val subscribedCatalog = CatalogKey.of("remote-feed")
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (id, tenant_key, name, type, source_url, created_at, updated_at)
                VALUES (:id, :tenantKey, :name, 'SUBSCRIBED', :sourceUrl, NOW(), NOW())
                """,
            )
                .bind("id", subscribedCatalog)
                .bind("tenantKey", tenant.id)
                .bind("name", "Remote Feed")
                .bind("sourceUrl", "https://example.com/catalog.json")
                .execute()
        }
        return tenantId to subscribedCatalog
    }

    private fun seedCodeList(
        tenantId: TenantId,
        catalog: CatalogKey,
        slug: String,
        display: String,
        sourceUrl: String? = null,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO code_lists (slug, tenant_key, catalog_key, display_name, source_type, source_url, auth_type, created_at, updated_at)
                VALUES (:slug, :tenantKey, :catalogKey, :displayName, :sourceType, :sourceUrl, 'NONE', NOW(), NOW())
                """,
            )
                .bind("slug", slug)
                .bind("tenantKey", tenantId.key)
                .bind("catalogKey", catalog)
                .bind("displayName", display)
                .bind("sourceType", if (sourceUrl == null) "INLINE" else "URL")
                .bind("sourceUrl", sourceUrl)
                .execute()
        }
    }

    private fun seedEntry(tenantId: TenantId, catalog: CatalogKey, slug: String, code: String, label: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO code_list_entries (tenant_key, catalog_key, code_list_slug, code, label, sort_order, hidden)
                VALUES (:tenantKey, :catalogKey, :slug, :code, :label, 0, FALSE)
                """,
            )
                .bind("tenantKey", tenantId.key)
                .bind("catalogKey", catalog)
                .bind("slug", slug)
                .bind("code", code)
                .bind("label", label)
                .execute()
        }
    }
}
