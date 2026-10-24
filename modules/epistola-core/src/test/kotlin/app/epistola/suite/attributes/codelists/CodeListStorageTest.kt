// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeListEntryHidden
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.CodeListEntryExists
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.revisions.ResourceContentCollector
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.CodeListStorageConversion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.default
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * A draft's code lists are stored as JSON (ADR 0033, step 2): one content per version,
 * `{ "resource": <wire form>, "source": {...} }`, named by a `release_resources` draft row. Entries
 * live only in the content, and lists, exists and hide run in SQL over it. A credential is stored
 * apart and named by id. The `code_lists` and `code_list_entries` tables are gone.
 */
class CodeListStorageTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var codeLists: CodeListStore

    @Autowired
    private lateinit var collector: ResourceContentCollector

    private val entries = listOf(CodeListEntry("b", "Bravo", 2), CodeListEntry("a", "Alpha", 1), CodeListEntry("c", "Charlie", 3, hidden = true))

    private fun payload(tenant: TenantId, key: String): String = jdbi.withHandle<String, Exception> {
        it.createQuery("SELECT payload::text FROM current_code_lists WHERE tenant_key = :t AND id = :k").bind("t", tenant.key).bind("k", key).mapTo(String::class.java).one()
    }

    private fun one(sql: String): String = jdbi.withHandle<String, Exception> { it.createQuery(sql).mapTo(String::class.java).one() }

    @Test
    fun `a code list is a draft row naming its JSON content, and the tables are gone`() {
        val tenant = TenantId(createTenant("Code list storage").id)
        val id = CodeListId(CodeListKey.of("letters"), CatalogId.default(tenant))
        withMediator { CreateCodeList(id, "Letters", description = "Phonetic", sourceType = CodeListSource.INLINE, entries = entries).execute() }

        val same = jdbi.withHandle<Boolean, Exception> { h ->
            h.createQuery("SELECT CAST(:stored AS JSONB) = CAST(:expected AS JSONB)")
                .bind("stored", payload(tenant, "letters"))
                .bind("expected", objectMapper.writeValueAsString(codeLists.payload("letters", CodeListFields("Letters", "Phonetic", entries, null))))
                .mapTo(Boolean::class.java).one()
        }
        assertThat(same).`as`("{ resource } only, entries in list order: %s", payload(tenant, "letters")).isTrue()
        assertThat(one("SELECT CAST(to_regclass('code_lists') IS NULL AND to_regclass('code_list_entries') IS NULL AS TEXT)")).isEqualTo("true")
    }

    @Test
    fun `entries are listed, found and hidden in SQL over the content, and a save returns what a read gives`() {
        val tenant = TenantId(createTenant("Code list entries").id)
        val id = CodeListId(CodeListKey.of("letters"), CatalogId.default(tenant))
        withMediator { CreateCodeList(id, "Letters", sourceType = CodeListSource.INLINE, entries = entries).execute() }

        withMediator {
            assertThat(ListCodeListEntries(id).query().map { it.code }).containsExactly("a", "b")
            assertThat(ListCodeListEntries(id, includeHidden = true).query().map { it.code }).containsExactly("a", "b", "c")
            assertThat(CodeListEntryExists(id.catalogId, id.key, "c").query()).`as`("a hidden entry still exists").isTrue()
            assertThat(CodeListEntryExists(id.catalogId, id.key, "z").query()).isFalse()

            val before = GetCodeList(id).query()!!
            assertThat(UpdateCodeListEntryHidden(id, "b", hidden = true).execute()).isTrue()
            assertThat(ListCodeListEntries(id).query().map { it.code }).containsExactly("a")
            assertThat(GetCodeList(id).query()!!.revision).`as`("hiding an entry changes the list").isEqualTo(before.revision + 1)
            assertThat(UpdateCodeListEntryHidden(id, "b", hidden = true).execute()).`as`("already hidden counts as found").isTrue()
            assertThat(GetCodeList(id).query()!!.revision).`as`("and changes nothing").isEqualTo(before.revision + 1)
            assertThat(UpdateCodeListEntryHidden(id, "z", hidden = true).execute()).isFalse()

            val saved = UpdateCodeList(id, "Letters, renamed", description = "Now described").execute()
            assertThat(saved).`as`("what a save returns is what a read gives").isEqualTo(GetCodeList(id).query())
        }
    }

    @Test
    fun `a credential is stored apart, named by id, and collected once no content names it`() {
        val tenant = TenantId(createTenant("Code list credential").id)
        val id = CodeListId(CodeListKey.of("remote"), CatalogId.default(tenant))
        withMediator {
            CreateCodeList(id, "Remote", sourceType = CodeListSource.URL, sourceUrl = "https://example.com/list.json", authType = AuthType.BEARER, credential = "first-secret").execute()
        }
        val first = one("SELECT credential_id::text FROM current_code_lists WHERE tenant_key = '${tenant.key.value}' AND id = 'remote'")
        assertThat(payload(tenant, "remote")).contains(first).doesNotContain("first-secret")

        val second = withMediator {
            UpdateCodeList(id, "Remote", sourceUrl = "https://example.com/list.json", authType = AuthType.BEARER, credential = "second-secret").execute()
            GetCodeList(id).query()!!
        }
        assertThat(second.credential?.value).isEqualTo("second-secret")

        // Past the grace period: a historical timestamp no command writes.
        jdbi.useHandle<Exception> { it.execute("UPDATE resource_credentials SET created_at = NOW() - INTERVAL '3 days' WHERE tenant_key = ?", tenant.key.value) }
        // The content naming the first credential is an orphan now; once it is collected, so is the credential.
        jdbi.useHandle<Exception> { it.execute("UPDATE resource_contents SET stored_at = NOW() - INTERVAL '3 days' WHERE tenant_key = ?", tenant.key.value) }
        val collected = collector.collect(tenant.key)

        assertThat(collected.credentials).isEqualTo(1)
        assertThat(one("SELECT count(*)::text FROM resource_credentials WHERE tenant_key = '${tenant.key.value}' AND credential_id = '$first'")).isEqualTo("0")
        assertThat(withMediator { GetCodeList(id).query()!! }.credential?.value).`as`("the one named stays").isEqualTo("second-secret")
    }

    @Test
    fun `the migration converts every bundled code list as the suite stores it, both ways`() {
        val tenant = createTenant("Code list conversion").id
        CodeListStorageConversion.verify(jdbi, objectMapper, codeLists, systemCatalog(tenant))
    }

    @Test
    fun `the migration converts a sourced code list with a credential as the suite stores it`() {
        val tenant = TenantId(createTenant("Code list conversion, sourced").id)
        val id = CodeListId(CodeListKey.of("remote"), CatalogId.default(tenant))
        withMediator {
            CreateCodeList(id, "Remote", description = "Every field", sourceType = CodeListSource.URL, sourceUrl = "https://example.com/list.json", authType = AuthType.API_KEY, credential = "s3cret").execute()
            UpdateCodeList(id, "Remote", description = "Every field", sourceUrl = "https://example.com/list.json", authType = AuthType.API_KEY, credential = "s3cret").execute()
        }
        CodeListStorageConversion.verify(jdbi, objectMapper, codeLists, id.catalogId)
    }
}
