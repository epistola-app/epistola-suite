// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.merge.MergeReleaseIntoDraft
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.revisions.ReleaseDescription
import app.epistola.suite.catalog.snapshot.BuildTenantSnapshot
import app.epistola.suite.catalog.snapshot.RestoreTenantSnapshot
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream

/**
 * A release remembers what it contains (ADR 0032): its base and the releases merged into its draft,
 * and "behind" follows that lineage, not numbers or times.
 *
 * The catalog is the demo's: 1.0.0 → 1.1.0 → 1.2.0 → 1.2.1 on the main line, and fixes 1.0.1 (made
 * from 1.0.0) and 1.1.1 (made from 1.1.0), both released before 1.2.0; the next release is on 1.2.1.
 */
class ReleaseMergeParentsTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), testCatalog(TenantId(tenant), catalog))

    private fun ref(tenant: TenantKey, catalog: CatalogKey, key: String) = CatalogReleaseRef(testCatalog(tenant, catalog), DraftRef.Id(key))

    /** The demo's lines; with [mergeFixIntoNext], 1.0.1 is merged into the next release before it is released as 1.2.0. */
    private fun demoLines(tenant: TenantKey, catalog: CatalogKey, mergeFixIntoNext: Boolean) = withMediator {
        CreateCatalog(tenantKey = tenant, id = catalog, name = "Lines").execute()
        CreateTheme(id = theme(tenant, catalog, "main"), name = "main 1.0.0").execute()
        CreateTheme(id = theme(tenant, catalog, "fixa"), name = "fix a").execute()
        CreateTheme(id = theme(tenant, catalog, "fixb"), name = "fix b").execute()
        MarkCatalogReady(testCatalog(tenant, catalog)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.0.0").execute()
        val fixA = ref(tenant, catalog, CreateCatalogDraft(testCatalog(tenant, catalog), "Fix A", "1.0.0").execute().versionId)

        UpdateTheme(theme(tenant, catalog, "main"), name = "main 1.1.0").execute()
        MarkCatalogReady(testCatalog(tenant, catalog)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.1.0").execute()
        val fixB = ref(tenant, catalog, CreateCatalogDraft(testCatalog(tenant, catalog), "Fix B", "1.1.0").execute().versionId)

        UpdateTheme(theme(tenant, catalog, "fixa"), name = "fix a, fixed", catalogVersion = fixA).execute()
        MarkCatalogReady(testCatalog(tenant, catalog), catalogVersion = fixA).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.0.1", catalogVersion = fixA).execute()
        UpdateTheme(theme(tenant, catalog, "fixb"), name = "fix b, fixed", catalogVersion = fixB).execute()
        MarkCatalogReady(testCatalog(tenant, catalog), catalogVersion = fixB).execute()
        // A fix to 1.1 does not contain the fix to 1.0: it is released behind it.
        ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.1.1", catalogVersion = fixB, acknowledgedBehind = setOf("1.0.1")).execute()

        if (mergeFixIntoNext) MergeReleaseIntoDraft(testCatalog(tenant, catalog), "1.0.1").execute()
        UpdateTheme(theme(tenant, catalog, "main"), name = "main 1.2.0").execute()
        MarkCatalogReady(testCatalog(tenant, catalog)).execute()
        val acknowledged = if (mergeFixIntoNext) setOf("1.1.1") else setOf("1.0.1", "1.1.1")
        ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.2.0", acknowledgedBehind = acknowledged).execute()
        UpdateTheme(theme(tenant, catalog, "main"), name = "main 1.2.1").execute()
        MarkCatalogReady(testCatalog(tenant, catalog)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.2.1", acknowledgedBehind = acknowledged).execute()
    }

    private fun merged(tenant: TenantKey, catalog: CatalogKey, version: String): List<ReleaseParent> = jdbi.withHandle<List<ReleaseParent>, Exception> {
        ReleaseLineage.mergedOf(it, testCatalog(tenant, catalog), version)
    }

    private fun nextBehind(tenant: TenantKey, catalog: CatalogKey): List<String> = ListCatalogReleases(testCatalog(tenant, catalog), CatalogReleaseStatus.DRAFT, withBehind = true).query()
        .single { it.isNext }.behind.map { it.version }

    @Test
    fun `the next release is behind every fix its line does not contain, however long ago it was released`() {
        val tenant = createTenant("Lines Unmerged").id
        val catalog = CatalogKey.of("lines-unmerged")
        demoLines(tenant, catalog, mergeFixIntoNext = false)

        withMediator {
            assertThat(nextBehind(tenant, catalog)).containsExactly("1.0.1", "1.1.1")
            UpdateTheme(theme(tenant, catalog, "main"), name = "main 1.2.2").execute()
            MarkCatalogReady(testCatalog(tenant, catalog)).execute()
            assertThat(runCatching { ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog)).execute() }.exceptionOrNull())
                .`as`("releasing asks for both")
                .isInstanceOfSatisfying(CatalogReleaseBehindException::class.java) { assertThat(it.behind).containsExactly("1.0.1", "1.1.1") }
        }
    }

    @Test
    fun `the next release says what each fix it lacks was made on, when it was published, and the line it continues`() {
        val tenant = createTenant("Lines Drawn").id
        val catalog = CatalogKey.of("lines-drawn")
        demoLines(tenant, catalog, mergeFixIntoNext = false)

        val next = withMediator { GetCatalogDraft(testCatalog(tenant, catalog), withBehind = true).query()!! }

        assertThat(next.behind.map { it.version to it.base }).containsExactly("1.0.1" to "1.0.0", "1.1.1" to "1.1.0")
        assertThat(next.behind).allSatisfy { assertThat(it.releasedAt).isNotNull() }
        assertThat(next.behind.first().changes).containsExactly(ReleasedChange("theme", "fixa", "modified", "fix a, fixed"))
        assertThat(next.behindLine).isEqualTo(
            BehindLine(
                line = listOf("1.0.0", "1.1.0", "1.2.0", "1.2.1"),
                joined = emptyList(),
                behind = listOf(BehindBranch("1.0.1", madeOn = "1.0.0"), BehindBranch("1.1.1", madeOn = "1.1.0")),
            ),
        )
    }

    @Test
    fun `a fix merged before a release is drawn joined where it was merged, and the line starts at the one still missing`() {
        val tenant = createTenant("Lines Drawn Merged").id
        val catalog = CatalogKey.of("lines-drawn-merged")
        demoLines(tenant, catalog, mergeFixIntoNext = true)

        val next = withMediator { GetCatalogDraft(testCatalog(tenant, catalog), withBehind = true).query()!! }

        assertThat(next.behindLine).isEqualTo(
            BehindLine(
                line = listOf("1.1.0", "1.2.0", "1.2.1"),
                joined = listOf(JoinedRelease("1.0.1", into = "1.2.0")),
                behind = listOf(BehindBranch("1.1.1", madeOn = "1.1.0")),
            ),
        )
    }

    @Test
    fun `a fix merged before a release stays contained after it, in the release and in what continues on it`() {
        val tenant = createTenant("Lines Merged").id
        val catalog = CatalogKey.of("lines-merged")
        demoLines(tenant, catalog, mergeFixIntoNext = true)

        withMediator {
            assertThat(nextBehind(tenant, catalog)).`as`("1.2.0 contains 1.0.1, so 1.2.1 and the next release do too").containsExactly("1.1.1")
            assertThat(PreviewDraftMerge(testCatalog(tenant, catalog), "1.0.1").query().alreadyMerged).isTrue()
            assertThat(merged(tenant, catalog, "1.2.0").map { it.version }).containsExactly("1.0.1")
        }
    }

    @Test
    fun `a release keeps its merge parents in its archive, and the installation receiving it does too`() {
        val publisher = createTenant("Lines Publisher").id
        val receiver = createTenant("Lines Receiver").id
        val catalog = CatalogKey.of("lines-shared")
        demoLines(publisher, catalog, mergeFixIntoNext = true)

        withMediator {
            val archive = ExportCatalogZip(testCatalog(publisher, catalog), "1.2.0").execute().zipBytes
            val release = CatalogArchiveReader.read(ByteArrayInputStream(archive)).archive!!.use { it.manifest.release }
            assertThat(ReleaseDescription.merged(release.metadata).map { it.version to it.fingerprint?.startsWith("v6:") })
                .`as`("named with its fingerprint in this suite's namespace")
                .containsExactly("1.0.1" to true)

            listOf("1.0.0", "1.0.1", "1.1.0", "1.2.0").forEach { version ->
                ImportCatalogZip(tenantKey = receiver, zipBytes = ExportCatalogZip(testCatalog(publisher, catalog), version).execute().zipBytes).execute()
            }

            assertThat(merged(receiver, catalog, "1.2.0").map { it.version }).containsExactly("1.0.1")
            assertThat(PreviewDraftMerge(testCatalog(receiver, catalog), "1.0.1").query().alreadyMerged).`as`("the received 1.2.0 contains it").isTrue()
            assertThat(nextBehind(receiver, catalog)).isEmpty()
        }
    }

    @Test
    fun `a restored tenant keeps what each release merged`() {
        val tenant = createTenant("Lines Restored").id
        val catalog = CatalogKey.of("lines-restored")
        demoLines(tenant, catalog, mergeFixIntoNext = true)
        val snapshot = withMediator { BuildTenantSnapshot(tenant).execute() }

        withMediator { RestoreTenantSnapshot(tenant, snapshot.bytes).execute() }

        withMediator {
            assertThat(merged(tenant, catalog, "1.2.0").map { it.version }).containsExactly("1.0.1")
            assertThat(nextBehind(tenant, catalog)).containsExactly("1.1.1")
        }
    }
}
