// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.DeleteCatalogRelease
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * ADR 0036, step (a): one row per catalog version, draft or released. Releasing a draft freezes its
 * row, keeping its id and key (contract ADR 0002: "The draft becomes released ... Its releaseId and its
 * name stay"); the next version is a new row holding what the release holds.
 */
class OneVersionTableTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")

    private fun brand(tenant: TenantKey) = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), letters))

    private data class Row(val versionId: UUID, val key: String, val state: String, val version: String?, val isNext: Boolean, val fingerprint: String?)

    private fun rows(tenant: TenantKey): List<Row> = jdbi.withHandle<List<Row>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT v.version_id, v.version_key, v.state, v.version, v.is_next, v.fingerprint FROM catalog_versions v
            JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id
            WHERE v.tenant_key = :t AND c.catalog_key = :c ORDER BY v.created_at, v.version_id
            """,
        ).bind("t", tenant).bind("c", letters)
            .map { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6)) }
            .list()
    }

    private fun next(tenant: TenantKey): Row = rows(tenant).single { it.isNext }

    private fun count(sql: String, tenant: TenantKey, versionId: UUID): Int = jdbi.withHandle<Int, Exception> {
        it.createQuery(sql).bind("t", tenant).bind("v", versionId).mapTo(Int::class.java).one()
    }

    private fun contentRows(tenant: TenantKey, versionId: UUID) = count("SELECT count(*) FROM release_resources WHERE tenant_key = :t AND version_id = :v", tenant, versionId)

    private fun pointer(tenant: TenantKey): UUID? = jdbi.withHandle<UUID?, Exception> {
        it.createQuery("SELECT next_version_id FROM catalogs WHERE tenant_key = :t AND catalog_key = :c")
            .bind("t", tenant).bind("c", letters).mapTo(UUID::class.java).findOne().orElse(null)
    }

    private fun withTheme(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(brand(tenant), "Brand").execute()
        tenant
    }

    @Test
    fun `releasing the next version freezes its row, keeping its id and key, and a new next version holds what it holds`() {
        val tenant = withTheme("one-version-freeze")
        val before = next(tenant)
        val heldBefore = contentRows(tenant, before.versionId)
        assertThat(heldBefore).isPositive()

        val version = withMediator { mediator.releaseNext(testCatalog(tenant, letters)) }

        val released = rows(tenant).single { it.versionId == before.versionId }
        assertThat(released.state).isEqualTo("released")
        assertThat(released.version).isEqualTo(version)
        assertThat(released.key).`as`("the release keeps the draft's id").isEqualTo(before.key)
        assertThat(released.isNext).isFalse()
        val releaseRecord = jdbi.withHandle<Pair<String, String>, Exception> {
            it.createQuery("SELECT r.version_key, r.fingerprint FROM catalog_releases r JOIN catalogs c ON c.catalog_id = r.catalog_id WHERE r.tenant_key = :t AND c.catalog_key = :c")
                .bind("t", tenant).bind("c", letters).map { rs, _ -> rs.getString(1) to rs.getString(2) }.one()
        }
        assertThat(releaseRecord).`as`("catalog_releases mirrors the released row").isEqualTo(before.key to released.fingerprint)

        val continued = next(tenant)
        assertThat(continued.versionId).isNotEqualTo(before.versionId)
        assertThat(continued.key).isNotEqualTo(before.key)
        assertThat(contentRows(tenant, continued.versionId)).`as`("the next version holds what the release holds").isEqualTo(heldBefore)
        assertThat(contentRows(tenant, released.versionId)).`as`("the draft's rows became the release's (step b)").isEqualTo(heldBefore)
        assertThat(count("SELECT count(*) FROM release_resources WHERE tenant_key = :t AND version_id = :v AND parent_id IS NULL AND fingerprint IS NULL", tenant, released.versionId))
            .`as`("each with its fingerprint").isZero()
        assertThat(count("SELECT count(*) FROM release_resources WHERE tenant_key = :t AND version_id = :v AND fingerprint IS NOT NULL", tenant, continued.versionId))
            .`as`("the next version's copy is a draft's, without fingerprints").isZero()
        assertThat(pointer(tenant)).`as`("catalogs.next_version_id follows").isEqualTo(continued.versionId)
    }

    @Test
    fun `a released parallel draft is the release it became, not deleted`() {
        val tenant = withTheme("one-version-parallel")
        withMediator { mediator.releaseNext(testCatalog(tenant, letters)) }
        val work = withMediator { CreateCatalogDraft(testCatalog(tenant, letters), "Redesign").execute() }
        val workRow = rows(tenant).single { it.key == work.versionId }
        withMediator {
            val at = CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Id(work.versionId))
            UpdateTheme(brand(tenant), name = "Brand, redesigned", catalogVersion = at).execute()
            MarkCatalogReady(testCatalog(tenant, letters), catalogVersion = at).execute()
            ReleaseCatalogDraft(testCatalog(tenant, letters), catalogVersion = at).execute()
        }

        val released = rows(tenant).single { it.versionId == workRow.versionId }
        assertThat(released.state).isEqualTo("released")
        assertThat(released.key).isEqualTo(work.versionId)
        assertThat(contentRows(tenant, released.versionId)).`as`("its rows are the release's").isPositive()
    }

    @Test
    fun `deleting a release removes its row`() {
        val tenant = withTheme("one-version-delete")
        val first = next(tenant)
        withMediator { mediator.releaseNext(testCatalog(tenant, letters)) }
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand, fixed").execute()
            mediator.releaseNext(testCatalog(tenant, letters))
        }

        withMediator { DeleteCatalogRelease(testCatalog(tenant, letters), "1.0.0").execute() }

        assertThat(rows(tenant).map { it.versionId }).doesNotContain(first.versionId)
        assertThat(rows(tenant).filter { it.state == "released" }.map { it.version }).containsExactly("1.0.1")
    }

    @Test
    fun `a version row is a draft without a number or a release with its record`() {
        val tenant = withTheme("one-version-checks")
        val draft = next(tenant)
        assertThatThrownBy {
            jdbi.useHandle<Exception> { it.createUpdate("UPDATE catalog_versions SET version = '9.9.9' WHERE version_id = :v").bind("v", draft.versionId).execute() }
        }.hasMessageContaining("chk_catalog_versions")
        assertThatThrownBy {
            jdbi.useHandle<Exception> { it.createUpdate("UPDATE catalog_versions SET state = 'released' WHERE version_id = :v").bind("v", draft.versionId).execute() }
        }.hasMessageContaining("chk_catalog_versions")
        assertThatThrownBy {
            jdbi.useHandle<Exception> { it.createUpdate("UPDATE catalog_versions SET origin = 'installed' WHERE version_id = :v").bind("v", draft.versionId).execute() }
        }.hasMessageContaining("chk_catalog_versions")
    }

    @Test
    fun `a declaration on another catalog's draft follows that row into the release, pinned (step c)`() {
        val tenant = withMediator {
            val tenant = createTenant("one-version-declared").id
            CreateCatalog(tenant, CatalogKey.of("parts"), "Parts").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), "parts")), "Brand").execute()
            CreateCatalog(tenant, letters, "Letters").execute()
            tenant
        }
        val parts = testCatalog(tenant, "parts")
        val partsDraft = withMediator { GetCatalogDraft(parts).query()!! }
        val partsDraftRow = jdbi.withHandle<UUID, Exception> {
            it.createQuery("SELECT version_id FROM catalog_versions WHERE tenant_key = :t AND version_key = :k").bind("t", tenant).bind("k", partsDraft.versionId).mapTo(UUID::class.java).one()
        }
        withMediator {
            val target = GetCatalog(parts).query()!!
            UpdateCatalogDependencies(testCatalog(tenant, letters), listOf(DeclaredCatalogDependency(target.registry, target.namespace, "parts", versionId = partsDraft.versionId))).execute()
        }

        val version = withMediator { mediator.releaseNext(parts) }

        val declared = jdbi.withHandle<Pair<UUID?, String?>, Exception> {
            it.createQuery(
                """
                SELECT d.target_version_id, d.release_version FROM catalog_version_dependencies d
                JOIN catalog_versions v ON v.version_id = d.version_id AND v.state = 'draft'
                JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
                WHERE d.tenant_key = :t AND c.catalog_key = 'letters'
                """,
            ).bind("t", tenant).map { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.one()
        }
        assertThat(declared).`as`("the draft's row, now the release, and its number").isEqualTo(partsDraftRow to version)
    }
}
