// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionBehindException
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Merging a release into a draft (ADR 0032). Two tenants play two installations: A releases 1.0.0,
 * B receives it, changes it and releases 2.0.0, and A, whose next version changed meanwhile, receives
 * 2.0.0 and merges it.
 */
class MergeReleaseIntoDraftTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var contentBuilder: CatalogContentBuilder

    @Autowired private lateinit var fingerprints: CatalogFingerprintService

    private data class Installations(val a: TenantKey, val catalog: CatalogKey)

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), CatalogId(catalog, TenantId(tenant)))

    /**
     * A's 1.0.0 has themes x, y and z. B's 2.0.0 changes x, adds w and removes z. A's next version
     * changes x differently and y, then A receives 2.0.0 beside its releases.
     */
    private fun diverged(name: String): Installations {
        val a = createTenant("$name A").id
        val b = createTenant("$name B").id
        val catalog = CatalogKey.of(name.lowercase().replace(' ', '-'))
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = name).execute()
            listOf("thx", "thy", "thz").forEach { CreateTheme(id = theme(a, catalog, it), name = "base $it").execute() }
            MarkCatalogReady(a, catalog).execute()
            ReleaseCatalogVersion(tenantKey = a, catalogKey = catalog, version = "1.0.0").execute()
            val v1 = ExportCatalogZip(a, catalog, "1.0.0").execute().zipBytes

            ImportCatalogZip(tenantKey = b, zipBytes = v1, catalogType = CatalogType.AUTHORED).execute()
            UpdateTheme(theme(b, catalog, "thx"), name = "B's x").execute()
            CreateTheme(id = theme(b, catalog, "thw"), name = "B's w").execute()
            DeleteTheme(theme(b, catalog, "thz")).execute()
            MarkCatalogReady(b, catalog).execute()
            ReleaseCatalogVersion(tenantKey = b, catalogKey = catalog, version = "2.0.0").execute()
            val v2 = ExportCatalogZip(b, catalog, "2.0.0").execute().zipBytes

            UpdateTheme(theme(a, catalog, "thx"), name = "A's x").execute()
            UpdateTheme(theme(a, catalog, "thy"), name = "A's y").execute()
            ImportCatalogZip(tenantKey = a, zipBytes = v2).execute()
        }
        return Installations(a, catalog)
    }

    private fun themeName(tenant: TenantKey, catalog: CatalogKey, key: String): String? = jdbi.withHandle<String?, Exception> { h ->
        // The next version's row of the theme: what the catalog's current version holds.
        h.createQuery(
            "SELECT t.name FROM themes t JOIN catalog_current_versions v ON v.tenant_key = t.tenant_key AND v.version_id = t.version_id " +
                "WHERE t.tenant_key = :t AND t.catalog_id = ${catalogAt("t", "c")} AND t.id = :k",
        )
            .bind("t", tenant).bind("c", catalog).bind("k", key)
            .mapTo(String::class.java).findOne().orElse(null)
    }

    @Test
    fun `the preview says per resource what the release changed, what the draft changed, and what conflicts`() {
        val (a, catalog) = diverged("Merge Preview")
        withMediator {
            val preview = PreviewDraftMerge(a, catalog, "2.0.0").query()

            assertThat(preview.base).isEqualTo("1.0.0")
            assertThat(preview.alreadyMerged).isFalse()
            assertThat(preview.resources.associate { it.contentKey to it.outcome }).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "theme/thw" to MergeOutcome.THEIRS,
                    "theme/thx" to MergeOutcome.CONFLICT,
                    "theme/thy" to MergeOutcome.OURS,
                    "theme/thz" to MergeOutcome.THEIRS,
                ),
            )
            assertThat(preview.resources.first { it.key == "thw" }.name).isEqualTo("B's w")
        }
    }

    @Test
    fun `a merge takes what only the release changed, applies the chosen side of a conflict, and the draft is no longer behind`() {
        val (a, catalog) = diverged("Merge Apply")
        withMediator {
            assertThatThrownBy { ReleaseCatalogVersion(tenantKey = a, catalogKey = catalog, version = "2.1.0").execute() }
                .`as`("behind 2.0.0 before the merge")
                .isInstanceOf(CatalogVersionBehindException::class.java)

            val merged = MergeReleaseIntoDraft(a, catalog, "2.0.0", resolutions = mapOf("theme/thx" to MergeSide.THEIRS)).execute()

            assertThat(merged.taken).containsExactlyInAnyOrder("theme/thw", "theme/thx")
            assertThat(merged.removed).containsExactly("theme/thz")
            assertThat(themeName(a, catalog, "thx")).isEqualTo("B's x")
            assertThat(themeName(a, catalog, "thw")).isEqualTo("B's w")
            assertThat(themeName(a, catalog, "thz")).isNull()
            assertThat(themeName(a, catalog, "thy")).`as`("ours, untouched").isEqualTo("A's y")

            // Taken resources hold exactly what the release has.
            val after = PreviewDraftMerge(a, catalog, "2.0.0").query()
            assertThat(after.alreadyMerged).isTrue()
            assertThat(after.resources.filter { it.key in setOf("thw", "thx", "thz") }.map { it.outcome }).containsOnly(MergeOutcome.SAME)
            assertThat(ListCatalogVersions(a, catalog, CatalogVersionStatus.DRAFT, withBehind = true).query().single { it.isNext }.behind).isEmpty()

            MarkCatalogReady(a, catalog).execute()
            ReleaseCatalogVersion(tenantKey = a, catalogKey = catalog, version = "2.1.0").execute()
            assertThat(ListCatalogVersions(a, catalog, CatalogVersionStatus.RELEASED).query().map { it.version }).contains("2.1.0")
        }
    }

    @Test
    fun `a conflict resolved to ours keeps the draft's`() {
        val (a, catalog) = diverged("Merge Ours")
        withMediator {
            val merged = MergeReleaseIntoDraft(a, catalog, "2.0.0", resolutions = mapOf("theme/thx" to MergeSide.OURS)).execute()

            assertThat(merged.kept).containsExactly("theme/thx")
            assertThat(themeName(a, catalog, "thx")).isEqualTo("A's x")
            assertThat(themeName(a, catalog, "thw")).isEqualTo("B's w")
        }
    }

    @Test
    fun `a conflict without a choice refuses the merge and changes nothing`() {
        val (a, catalog) = diverged("Merge Unresolved")
        withMediator {
            assertThatThrownBy { MergeReleaseIntoDraft(a, catalog, "2.0.0").execute() }
                .isInstanceOf(MergeConflictsUnresolvedException::class.java)
                .hasMessageContaining("theme/thx")
            assertThatThrownBy { MergeReleaseIntoDraft(a, catalog, "2.0.0", resolutions = mapOf("theme/thy" to MergeSide.THEIRS, "theme/thx" to MergeSide.THEIRS)).execute() }
                .`as`("a resolution for what is no conflict")
                .isInstanceOf(MergeNotApplicableException::class.java)
                .hasMessageContaining("theme/thy")

            assertThat(themeName(a, catalog, "thw")).isNull()
            assertThat(themeName(a, catalog, "thz")).isEqualTo("base thz")
            assertThat(PreviewDraftMerge(a, catalog, "2.0.0").query().alreadyMerged).isFalse()
        }
    }

    @Test
    fun `a release that removes what the draft still uses is refused`() {
        val (a, catalog) = diverged("Merge In Use")
        withMediator {
            val template = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(catalog, TenantId(a)))
            CreateDocumentTemplate(id = template, name = "Uses z").execute()
            UpdateDocumentTemplate(id = template, themeId = ThemeKey.of("thz"), themeCatalogKey = catalog).execute()

            assertThatThrownBy { MergeReleaseIntoDraft(a, catalog, "2.0.0", resolutions = mapOf("theme/thx" to MergeSide.THEIRS)).execute() }
                .isInstanceOf(MergeRefusedException::class.java)
                .hasMessageContaining("thz")
            assertThat(themeName(a, catalog, "thz")).isEqualTo("base thz")
            assertThat(themeName(a, catalog, "thw")).`as`("nothing was taken either").isNull()
        }
    }

    @Test
    fun `a release the draft already contains is refused`() {
        val (a, catalog) = diverged("Merge Twice")
        withMediator {
            assertThatThrownBy { MergeReleaseIntoDraft(a, catalog, "1.0.0").execute() }
                .`as`("its base")
                .isInstanceOf(MergeNotApplicableException::class.java)
                .hasMessageContaining("already contains 1.0.0")
            MergeReleaseIntoDraft(a, catalog, "2.0.0", resolutions = mapOf("theme/thx" to MergeSide.OURS)).execute()
            assertThatThrownBy { MergeReleaseIntoDraft(a, catalog, "2.0.0").execute() }
                .isInstanceOf(MergeNotApplicableException::class.java)
                .hasMessageContaining("already contains 2.0.0")
        }
    }
}
