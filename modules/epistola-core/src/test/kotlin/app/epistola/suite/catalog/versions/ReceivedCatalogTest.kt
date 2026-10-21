// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CatalogHasLocalChangesException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.merge.MergeReleaseIntoDraft
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.queries.CatalogReadOnlyReason
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A catalog received from elsewhere (ADR 0032): it has a next version on the release it installed,
 * can be changed and released like any other, and a newer release it receives moves its next version
 * along when that has no changes of its own, or is recorded beside it to be merged when it has.
 * Two tenants play two installations; the release travels as an archive.
 */
class ReceivedCatalogTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var nextVersionChanges: NextVersionChanges

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), CatalogId(catalog, TenantId(tenant)))

    /** The author's catalog with a theme `brand`, released as [version]; its archive. */
    private fun authored(author: TenantKey, catalog: CatalogKey): ByteArray {
        CreateCatalog(tenantKey = author, id = catalog, name = catalog.value).execute()
        CreateTheme(id = theme(author, catalog, "brand"), name = "Brand").execute()
        MarkCatalogReady(author, catalog).execute()
        ReleaseCatalogVersion(tenantKey = author, catalogKey = catalog, version = "1.0.0").execute()
        return ExportCatalogZip(author, catalog, "1.0.0").execute().zipBytes
    }

    /** The author's next release, the theme renamed to [name]; its archive. */
    private fun nextRelease(author: TenantKey, catalog: CatalogKey, version: String, name: String): ByteArray {
        UpdateTheme(theme(author, catalog, "brand"), name = name).execute()
        MarkCatalogReady(author, catalog).execute()
        ReleaseCatalogVersion(tenantKey = author, catalogKey = catalog, version = version).execute()
        return ExportCatalogZip(author, catalog, version).execute().zipBytes
    }

    private fun themeName(tenant: TenantKey, catalog: CatalogKey): String? = jdbi.withHandle<String?, Exception> { h ->
        h.createQuery(
            "SELECT t.name FROM themes t JOIN catalog_current_versions v ON v.tenant_key = t.tenant_key AND v.version_id = t.version_id " +
                "WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")} AND t.id = 'brand'",
        ).bind("t", tenant).bind("c", catalog).mapTo(String::class.java).findOne().orElse(null)
    }

    private fun next(tenant: TenantKey, catalog: CatalogKey) = ListCatalogVersions(tenant, catalog, CatalogVersionStatus.DRAFT, withBehind = true).query().single { it.isNext }

    @Test
    fun `a received catalog has a next version on the release it installed, unchanged, and can be changed and released`() {
        val author = createTenant("Received A").id
        val receiver = createTenant("Received B").id
        val catalog = CatalogKey.of("received-edit")
        withMediator {
            ImportCatalogZip(receiver, authored(author, catalog), CatalogType.SUBSCRIBED).execute()

            assertThat(next(receiver, catalog).base).isEqualTo("1.0.0")
            assertThat(nextVersionChanges.changed(receiver, catalog)).`as`("what was installed is the release").isEmpty()
            assertThat(GetCatalogEditability(receiver, catalog).query().editable).isTrue()

            UpdateTheme(theme(receiver, catalog, "brand"), name = "Brand, ours").execute()
            assertThat(nextVersionChanges.changed(receiver, catalog)).containsExactly("theme/brand")
            MarkCatalogReady(receiver, catalog).execute()
            ReleaseCatalogVersion(tenantKey = receiver, catalogKey = catalog, version = "1.0.1").execute()
            assertThat(ListCatalogReleases(receiver, catalog).query().map { it.version }).containsExactly("1.0.1", "1.0.0")
        }
    }

    @Test
    fun `a newer release moves an unchanged next version along`() {
        val author = createTenant("Received FF A").id
        val receiver = createTenant("Received FF B").id
        val catalog = CatalogKey.of("received-ff")
        withMediator {
            ImportCatalogZip(receiver, authored(author, catalog), CatalogType.SUBSCRIBED).execute()
            ImportCatalogZip(receiver, nextRelease(author, catalog, "1.0.1", "Brand 2")).execute()

            assertThat(next(receiver, catalog).base).isEqualTo("1.0.1")
            assertThat(themeName(receiver, catalog)).isEqualTo("Brand 2")
            assertThat(nextVersionChanges.changed(receiver, catalog)).isEmpty()
        }
    }

    @Test
    fun `a newer release beside a changed next version is recorded, leaves it behind, and merges in`() {
        val author = createTenant("Received Merge A").id
        val receiver = createTenant("Received Merge B").id
        val catalog = CatalogKey.of("received-merge")
        withMediator {
            ImportCatalogZip(receiver, authored(author, catalog), CatalogType.SUBSCRIBED).execute()
            CreateTheme(id = theme(receiver, catalog, "ours"), name = "Ours").execute()

            ImportCatalogZip(receiver, nextRelease(author, catalog, "1.0.1", "Brand 2")).execute()

            assertThat(ListCatalogReleases(receiver, catalog).query().map { it.version }).containsExactly("1.0.1", "1.0.0")
            assertThat(next(receiver, catalog).let { it.base to it.behind.map { b -> b.version } }).isEqualTo("1.0.0" to listOf("1.0.1"))
            assertThat(themeName(receiver, catalog)).`as`("the next version keeps what it had").isEqualTo("Brand")

            MergeReleaseIntoDraft(receiver, catalog, "1.0.1").execute()
            assertThat(themeName(receiver, catalog)).isEqualTo("Brand 2")
            assertThat(PreviewDraftMerge(receiver, catalog, "1.0.1").query().alreadyMerged).isTrue()
            assertThat(next(receiver, catalog).behind).isEmpty()
        }
    }

    @Test
    fun `an upgrade from the source is refused while the next version has changes of its own`() {
        val tenant = createTenant("Received Upgrade").id
        withMediator {
            val author = createTenant("Received Upgrade A").id
            val catalog = CatalogKey.of("received-upgrade")
            ImportCatalogZip(tenant, authored(author, catalog), CatalogType.SUBSCRIBED).execute()
            UpdateTheme(theme(tenant, catalog, "brand"), name = "Brand, ours").execute()

            assertThatThrownBy { UpgradeCatalog(tenant, catalog).execute() }
                .`as`("refused before its source is asked, so a catalog from an archive shows it too")
                .isInstanceOf(CatalogHasLocalChangesException::class.java)
                .hasMessageContaining("theme/brand")
        }
    }

    @Test
    fun `a catalog the suite supplies cannot be changed`() {
        val tenant = createTenant("Received System").id
        withMediator {
            assertThat(GetCatalogEditability(tenant, SYSTEM_CATALOG_KEY).query().reason).isEqualTo(CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA)
            assertThatThrownBy { CreateTheme(id = theme(tenant, SYSTEM_CATALOG_KEY, "mine"), name = "Mine").execute() }
                .isInstanceOf(CatalogReadOnlyException::class.java)
                .hasMessageContaining("Epistola supplies this catalog")
            assertThat(next(tenant, SYSTEM_CATALOG_KEY).base).`as`("its next version is on the release it installed").isNotNull()
        }
    }

    @Test
    fun `the editability of every catalog at once agrees with each one's`() {
        val author = createTenant("Received List A").id
        val receiver = createTenant("Received List B").id
        val catalog = CatalogKey.of("received-list")
        withMediator {
            ImportCatalogZip(receiver, authored(author, catalog), CatalogType.SUBSCRIBED).execute()

            val all = ListCatalogEditability(receiver).query()
            assertThat(all.byCatalog.keys).contains(catalog, SYSTEM_CATALOG_KEY, CatalogKey.DEFAULT)
            all.byCatalog.forEach { (key, editability) -> assertThat(editability).`as`(key.value).isEqualTo(GetCatalogEditability(receiver, key).query()) }
            assertThat(all.editableKeys).contains(catalog, CatalogKey.DEFAULT).doesNotContain(SYSTEM_CATALOG_KEY)
            assertThat(all.of(SYSTEM_CATALOG_KEY)?.reason).isEqualTo(CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA)
            assertThat(all.editable(CatalogKey.of("not-here"))).isFalse()
        }
    }

    @Test
    fun `an import that names a type only for a new catalog creates one received, and leaves a catalog the tenant has alone`() {
        val author = createTenant("Received New A").id
        val receiver = createTenant("Received New B").id
        val catalog = CatalogKey.of("received-new")
        withMediator {
            val archive = authored(author, catalog)
            ImportCatalogZip(receiver, archive, newCatalogType = CatalogType.SUBSCRIBED).execute()
            assertThat(GetCatalog(receiver, catalog).query()?.type).isEqualTo(CatalogType.SUBSCRIBED)

            // The author's own catalog, made here: the same import is no conflict, and it keeps its type.
            ImportCatalogZip(author, archive, newCatalogType = CatalogType.SUBSCRIBED).execute()
            assertThat(GetCatalog(author, catalog).query()?.type).isEqualTo(CatalogType.AUTHORED)
        }
    }
}
