// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogNotUpgradeableException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.merge.MergeReleaseIntoDraft
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.queries.CatalogReadOnlyReason
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.catalog.queries.GetCatalogOverview
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
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
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.atNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withoutSystemCatalog
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

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), testCatalog(TenantId(tenant), catalog))

    /** The author's catalog with a theme `brand`, released as [version]; its archive. */
    private fun authored(author: TenantKey, catalog: CatalogKey): ByteArray {
        CreateCatalog(tenantKey = author, id = catalog, name = catalog.value).execute()
        withoutSystemCatalog(testCatalog(author, catalog))
        CreateTheme(id = theme(author, catalog, "brand"), name = "Brand", catalogVersion = null).execute()
        MarkCatalogReady(testCatalog(author, catalog), catalogVersion = null).execute()
        ReleaseCatalogDraft(catalog = testCatalog(author, catalog), version = "1.0.0", catalogVersion = null).execute()
        return ExportCatalogZip(testCatalog(author, catalog), "1.0.0").execute().zipBytes
    }

    /** The author's next release, the theme renamed to [name]; its archive. */
    private fun nextRelease(author: TenantKey, catalog: CatalogKey, version: String, name: String): ByteArray {
        UpdateTheme(theme(author, catalog, "brand"), name = name, catalogVersion = null).execute()
        MarkCatalogReady(testCatalog(author, catalog), catalogVersion = null).execute()
        ReleaseCatalogDraft(catalog = testCatalog(author, catalog), version = version, catalogVersion = null).execute()
        return ExportCatalogZip(testCatalog(author, catalog), version).execute().zipBytes
    }

    private fun themeName(tenant: TenantKey, catalog: CatalogKey): String? = jdbi.withHandle<String?, Exception> { h ->
        h.createQuery(
            "SELECT t.name FROM ${atNext("version_themes")} t " +
                "WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")} AND t.id = 'brand'",
        ).bind("t", tenant).bindCatalog("c", testCatalog(tenant, catalog)).mapTo(String::class.java).findOne().orElse(null)
    }

    private fun next(tenant: TenantKey, catalog: CatalogKey) = ListCatalogReleases(testCatalog(tenant, catalog), CatalogReleaseStatus.DRAFT, withBehind = true).query().single { it.isNext }

    @Test
    fun `a received catalog has a next version on the release it installed, unchanged, and can be changed and released`() {
        val author = createTenant("Received A").id
        val receiver = createTenant("Received B").id
        val catalog = CatalogKey.of("received-edit")
        withMediator {
            ImportCatalogZip(receiver, authored(author, catalog), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(next(receiver, catalog).base).isEqualTo("1.0.0")
            assertThat(nextVersionChanges.changed(testCatalog(receiver, catalog))).`as`("what was installed is the release").isEmpty()
            assertThat(GetCatalogEditability(testCatalog(receiver, catalog)).query().editable).isTrue()

            UpdateTheme(theme(receiver, catalog, "brand"), name = "Brand, ours", catalogVersion = null).execute()
            assertThat(nextVersionChanges.changed(testCatalog(receiver, catalog))).containsExactly("theme/brand")
            MarkCatalogReady(testCatalog(receiver, catalog), catalogVersion = null).execute()
            // Releasing it here needs its namespace: the two tenants share it, as test and production do.
            TestNamespaces.share(author, receiver).execute()
            ReleaseCatalogDraft(catalog = testCatalog(receiver, catalog), version = "1.0.1", catalogVersion = null).execute()
            assertThat(ListCatalogReleaseSummaries(testCatalog(receiver, catalog)).query().map { it.version }).containsExactly("1.0.1", "1.0.0")
        }
    }

    @Test
    fun `a newer release moves an unchanged next version along`() {
        val author = createTenant("Received FF A").id
        val receiver = createTenant("Received FF B").id
        val catalog = CatalogKey.of("received-ff")
        withMediator {
            ImportCatalogZip(receiver, authored(author, catalog), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            ImportCatalogZip(receiver, nextRelease(author, catalog, "1.0.1", "Brand 2"), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(next(receiver, catalog).base).isEqualTo("1.0.1")
            assertThat(themeName(receiver, catalog)).isEqualTo("Brand 2")
            assertThat(nextVersionChanges.changed(testCatalog(receiver, catalog))).isEmpty()
        }
    }

    @Test
    fun `a newer release beside a changed next version is recorded, leaves it behind, and merges in`() {
        val author = createTenant("Received Merge A").id
        val receiver = createTenant("Received Merge B").id
        val catalog = CatalogKey.of("received-merge")
        withMediator {
            ImportCatalogZip(receiver, authored(author, catalog), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            CreateTheme(id = theme(receiver, catalog, "ours"), name = "Ours", catalogVersion = null).execute()

            ImportCatalogZip(receiver, nextRelease(author, catalog, "1.0.1", "Brand 2"), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(ListCatalogReleaseSummaries(testCatalog(receiver, catalog)).query().map { it.version }).containsExactly("1.0.1", "1.0.0")
            assertThat(next(receiver, catalog).let { it.base to it.behind.map { b -> b.version } }).isEqualTo("1.0.0" to listOf("1.0.1"))
            assertThat(themeName(receiver, catalog)).`as`("the next version keeps what it had").isEqualTo("Brand")

            MergeReleaseIntoDraft(testCatalog(receiver, catalog), "1.0.1", catalogVersion = null).execute()
            assertThat(themeName(receiver, catalog)).isEqualTo("Brand 2")
            assertThat(PreviewDraftMerge(testCatalog(receiver, catalog), "1.0.1", catalogVersion = null).query().alreadyMerged).isTrue()
            assertThat(next(receiver, catalog).behind).isEmpty()
        }
    }

    @Test
    fun `a newer release beside a next version whose only change is a catalog setting is recorded, keeping the setting`() {
        val author = createTenant("Received Setting A").id
        val receiver = createTenant("Received Setting B").id
        val catalog = CatalogKey.of("received-setting")
        withMediator {
            ImportCatalogZip(receiver, authored(author, catalog), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            // The only change here: the catalog's default theme.
            SetCatalogDefaultTheme(testCatalog(receiver, catalog), CatalogDefaultTheme(ThemeKey.of("brand"), testCatalog(receiver, catalog)), catalogVersion = null).execute()
            assertThat(nextVersionChanges.changed(testCatalog(receiver, catalog))).containsExactly("catalog/defaultTheme")

            ImportCatalogZip(receiver, nextRelease(author, catalog, "1.0.1", "Brand 2"), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(next(receiver, catalog).let { it.base to it.behind.map { b -> b.version } }).isEqualTo("1.0.0" to listOf("1.0.1"))
            assertThat(GetCatalogThemeSettings(testCatalog(receiver, catalog), catalogVersion = null).query().defaultTheme?.themeKey).isEqualTo(ThemeKey.of("brand"))
        }
    }

    @Test
    fun `a catalog received from an archive has no source to upgrade from`() {
        val tenant = createTenant("Received Upgrade").id
        withMediator {
            val author = createTenant("Received Upgrade A").id
            val catalog = CatalogKey.of("received-upgrade")
            ImportCatalogZip(tenant, authored(author, catalog), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThatThrownBy { UpgradeCatalog(testCatalog(tenant, catalog)).execute() }
                .isInstanceOf(CatalogNotUpgradeableException::class.java)
        }
    }

    @Test
    fun `a catalog the suite supplies cannot be changed`() {
        val tenant = createTenant("Received System").id
        withMediator {
            assertThat(GetCatalogEditability(testCatalog(tenant, SYSTEM_CATALOG_KEY)).query().reason).isEqualTo(CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA)
            assertThatThrownBy { CreateTheme(id = theme(tenant, SYSTEM_CATALOG_KEY, "mine"), name = "Mine", catalogVersion = null).execute() }
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
            ImportCatalogZip(receiver, authored(author, catalog), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            val all = ListCatalogEditability(receiver).query()
            assertThat(all.byCatalog.keys.map { it.key }).contains(catalog, SYSTEM_CATALOG_KEY, CatalogKey.DEFAULT)
            all.byCatalog.forEach { (address, editability) -> assertThat(editability).`as`(address.toString()).isEqualTo(GetCatalogEditability(testCatalog(receiver, address.key)).query()) }
            assertThat(all.editableCatalogs.map { it.key }).contains(catalog, CatalogKey.DEFAULT).doesNotContain(SYSTEM_CATALOG_KEY)
            assertThat(all.of(SYSTEM_CATALOG_KEY.value)?.reason).isEqualTo(CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA)
            assertThat(all.of("not-here")?.editable == true).isFalse()
        }
    }

    @Test
    fun `an import creates the catalog it names, editable, with the release recorded, ready, and a next version on it`() {
        val author = createTenant("Received New A").id
        val receiver = createTenant("Received New B").id
        val catalog = CatalogKey.of("received-new")
        withMediator {
            val archive = authored(author, catalog)
            val published = ListCatalogReleaseSummaries(testCatalog(author, catalog)).query().single().fingerprint

            assertThat(ImportCatalogZip(receiver, archive, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute().newlyInstalled).isTrue()

            val received = GetCatalog(testCatalog(receiver, catalog)).query()!!
            assertThat(received.namespace).`as`("named as its archive names it").isEqualTo(GetCatalog(testCatalog(author, catalog)).query()!!.namespace)
            assertThat(received.latestReleaseVersion).isEqualTo("1.0.0")
            assertThat(ListCatalogReleaseSummaries(testCatalog(receiver, catalog)).query().map { it.version to it.fingerprint }).containsExactly("1.0.0" to published)
            assertThat(next(receiver, catalog).base).isEqualTo("1.0.0")
            assertThat(GetCatalogEditability(testCatalog(receiver, catalog)).query().editable).isTrue()
            assertThat(GetCatalogOverview(receiver).query().single { it.catalogKey == catalog }.notReady)
                .`as`("what a release brings is released content, ready as it is")
                .isZero()
        }
    }

    @Test
    fun `importing a release into the tenant that released it changes nothing`() {
        val author = createTenant("Received Own A").id
        val catalog = CatalogKey.of("received-own")
        withMediator {
            val archive = authored(author, catalog)

            assertThat(ImportCatalogZip(author, archive, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute().newlyInstalled).isFalse()

            val own = GetCatalog(testCatalog(author, catalog)).query()!!
            assertThat(own.latestReleaseVersion).isEqualTo("1.0.0")
            assertThat(ListCatalogReleaseSummaries(testCatalog(author, catalog)).query().map { it.version }).containsExactly("1.0.0")
            assertThat(next(author, catalog).base).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `a release with exactly what a never-released next version holds moves it along, and so do later ones`() {
        // Test and acceptance upgraded from the same 1.x data: each catalog's next version holds the
        // same content and has no base, as the 2.0 upgrade cuts no release. Test releases, acceptance
        // imports that release.
        val author = createTenant("Received Same A").id
        val receiver = createTenant("Received Same B").id
        val catalog = CatalogKey.of("received-same")
        withMediator {
            val archive = authored(author, catalog)
            val namespace = GetCatalog(testCatalog(author, catalog)).query()!!.namespace
            TestNamespaces.share(author, receiver).execute()
            CreateCatalog(tenantKey = receiver, id = catalog, name = catalog.value, namespace = namespace).execute()
            withoutSystemCatalog(testCatalog(receiver, catalog))
            CreateTheme(id = theme(receiver, catalog, "brand"), name = "Brand", catalogVersion = null).execute()

            ImportCatalogZip(receiver, archive, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(next(receiver, catalog).base).`as`("nothing of its own to keep, so it moves to the release").isEqualTo("1.0.0")
            assertThat(nextVersionChanges.changed(testCatalog(receiver, catalog))).isEmpty()

            ImportCatalogZip(receiver, nextRelease(author, catalog, "1.0.1", "Brand 2"), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(next(receiver, catalog).base).isEqualTo("1.0.1")
            assertThat(themeName(receiver, catalog)).isEqualTo("Brand 2")
        }
    }

    @Test
    fun `a never-released next version that leaves a dependency to be chosen at release moves to the release that pinned it`() {
        // As the 2.0 upgrade leaves a 1.x catalog's declarations: the release each uses is chosen
        // when it is released. Test released, which pinned them; acceptance has the same content.
        val author = createTenant("Received Pin A").id
        val receiver = createTenant("Received Pin B").id
        val dependency = CatalogKey.of("received-pin-dep")
        val catalog = CatalogKey.of("received-pin")
        withMediator {
            val dependencyArchive = authored(author, dependency)
            val dependencyName = GetCatalog(testCatalog(author, dependency)).query()!!
            CreateCatalog(tenantKey = author, id = catalog, name = catalog.value).execute()
            withoutSystemCatalog(testCatalog(author, catalog))
            CreateTheme(id = theme(author, catalog, "brand"), name = "Brand", catalogVersion = null).execute()
            val pinned = DeclaredCatalogDependency(dependencyName.registry, dependencyName.namespace, dependency.value, releaseVersion = "1.0.0")
            UpdateCatalogDependencies(testCatalog(author, catalog), listOf(pinned), catalogVersion = null).execute()
            MarkCatalogReady(testCatalog(author, catalog), catalogVersion = null).execute()
            ReleaseCatalogDraft(catalog = testCatalog(author, catalog), version = "1.0.0", catalogVersion = null).execute()
            val archive = ExportCatalogZip(testCatalog(author, catalog), "1.0.0").execute().zipBytes

            ImportCatalogZip(receiver, dependencyArchive, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()
            TestNamespaces.share(author, receiver).execute()
            val namespace = GetCatalog(testCatalog(author, catalog)).query()!!.namespace
            CreateCatalog(tenantKey = receiver, id = catalog, name = catalog.value, namespace = namespace).execute()
            withoutSystemCatalog(testCatalog(receiver, catalog))
            CreateTheme(id = theme(receiver, catalog, "brand"), name = "Brand", catalogVersion = null).execute()
            UpdateCatalogDependencies(testCatalog(receiver, catalog), listOf(pinned), catalogVersion = null).execute()
            // No command declares a dependency without a release or a draft; only the 2.0 upgrade
            // writes one (a 1.x catalog's, chosen when it is released), so the test unpins it in SQL.
            jdbi.useHandle<Exception> { h ->
                h.createUpdate(
                    "UPDATE catalog_version_dependencies SET release_version = NULL, release_fingerprint = NULL " +
                        "WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} " +
                        "AND version_id IN (SELECT v.version_id FROM catalog_versions v WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.version_key = :v)",
                ).bind("t", receiver).bindCatalog("c", testCatalog(receiver, catalog)).bind("v", next(receiver, catalog).versionId).execute()
            }

            ImportCatalogZip(receiver, archive, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(next(receiver, catalog).base).isEqualTo("1.0.0")
            assertThat(nextVersionChanges.changed(testCatalog(receiver, catalog))).isEmpty()
        }
    }

    @Test
    fun `a never-released next version with content of its own still keeps it when a release arrives`() {
        val author = createTenant("Received Own A").id
        val receiver = createTenant("Received Own B").id
        val catalog = CatalogKey.of("received-own")
        withMediator {
            val archive = authored(author, catalog)
            val namespace = GetCatalog(testCatalog(author, catalog)).query()!!.namespace
            TestNamespaces.share(author, receiver).execute()
            CreateCatalog(tenantKey = receiver, id = catalog, name = catalog.value, namespace = namespace).execute()
            withoutSystemCatalog(testCatalog(receiver, catalog))
            CreateTheme(id = theme(receiver, catalog, "brand"), name = "Brand, edited here", catalogVersion = null).execute()

            ImportCatalogZip(receiver, archive, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(next(receiver, catalog).base).isNull()
            assertThat(themeName(receiver, catalog)).isEqualTo("Brand, edited here")
        }
    }

    @Test
    fun `a release imported into a catalog made here, never released, is recorded beside and leaves its work alone`() {
        val author = createTenant("Received Work A").id
        val receiver = createTenant("Received Work B").id
        val catalog = CatalogKey.of("received-work")
        withMediator {
            val archive = authored(author, catalog)
            val namespace = GetCatalog(testCatalog(author, catalog)).query()!!.namespace
            TestNamespaces.share(author, receiver).execute()
            CreateCatalog(tenantKey = receiver, id = catalog, name = "Ours", namespace = namespace).execute()
            withoutSystemCatalog(testCatalog(receiver, catalog))
            CreateTheme(id = theme(receiver, catalog, "ours"), name = "Ours", catalogVersion = null).execute()

            ImportCatalogZip(receiver, archive, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute()

            assertThat(ListCatalogReleaseSummaries(testCatalog(receiver, catalog)).query().map { it.version }).containsExactly("1.0.0")
            assertThat(next(receiver, catalog).base).`as`("the next version stays where it was").isNull()
            assertThat(nextVersionChanges.changed(testCatalog(receiver, catalog))).`as`("everything a next version without a base holds is its own").containsExactly("theme/ours")
            assertThat(
                jdbi.withHandle<Int, Exception> { h ->
                    h.createQuery("SELECT count(*) FROM ${atNext("version_themes")} t WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")} AND t.id = 'ours'")
                        .bind("t", receiver).bindCatalog("c", testCatalog(receiver, catalog)).mapTo(Int::class.java).one()
                },
            ).`as`("its work is not pruned").isEqualTo(1)
        }
    }
}
