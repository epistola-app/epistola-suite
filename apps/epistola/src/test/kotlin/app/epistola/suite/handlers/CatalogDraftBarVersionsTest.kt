// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.CatalogSegment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * The catalog page's view of an authored catalog's versions (docs/catalog-workspace-navigation.md):
 * the next version, other drafts, the published versions, and when an in-between version can start.
 */
class CatalogDraftBarVersionsTest {

    private val letters = CatalogKey.of("letters")

    private fun draft(version: String, base: String?) = CatalogVersion(version, CatalogVersionStatus.DRAFT, base = base)

    private fun release(version: String, retained: Boolean = true, liveIn: List<String> = emptyList(), base: String? = null) = CatalogVersion(
        version,
        CatalogVersionStatus.RELEASED,
        base = base,
        releasedAt = OffsetDateTime.parse("2026-10-01T10:00:00Z"),
        contentRetained = retained,
        deployedTo = liveIn,
    )

    private fun bar(
        drafts: List<CatalogVersion>,
        releases: List<CatalogVersion>,
        segment: CatalogSegment = CatalogSegment(letters),
        mayEdit: Boolean = true,
    ) = CatalogDraftBar(
        tenantKey = TenantKey.of("acme"),
        catalogKey = letters,
        catalogName = "Letters",
        authored = true,
        segment = segment,
        drafts = drafts,
        releases = releases,
        chosen = segment.version?.let { v -> drafts.first { it.version == v } } ?: drafts.firstOrNull(),
        mayOpenDrafts = mayEdit,
        mayDiscardDrafts = true,
        pageAt = { "/tenants/acme/catalogs/${it.value}/draft" },
    )

    @Test
    fun `versions list the next version first, then other drafts, then published versions newest first, each to its own page`() {
        val rows = bar(
            drafts = listOf(draft("1.3.0", "1.2.0"), draft("1.2.1", "1.2.0")),
            releases = listOf(release("1.2.0", liveIn = listOf("production")), release("1.1.0")),
        ).versionRows

        assertThat(rows.map { Triple(it.version, it.next, it.draft) }).containsExactly(
            Triple("1.3.0", true, true),
            Triple("1.2.1", false, true),
            Triple("1.2.0", false, false),
            Triple("1.1.0", false, false),
        )
        assertThat(rows.map { it.href }).containsExactly(
            "/tenants/acme/catalogs/letters/draft",
            "/tenants/acme/catalogs/letters@1.2.1/draft",
            "/tenants/acme/catalogs/letters/releases/1.2.0",
            "/tenants/acme/catalogs/letters/releases/1.1.0",
        )
        assertThat(rows[2].liveIn).containsExactly("production")
    }

    @Test
    fun `every version says the published version it started from, a published one too`() {
        val rows = bar(
            drafts = listOf(draft("1.3.0", "1.2.0")),
            releases = listOf(release("1.2.0", base = "1.1.0"), release("1.1.0")),
        ).versionRows

        assertThat(rows.map { it.version to it.base }).containsExactly("1.3.0" to "1.2.0", "1.2.0" to "1.1.0", "1.1.0" to null)
    }

    @Test
    fun `the next version is called so, another draft by its number`() {
        val drafts = listOf(draft("1.3.0", "1.2.0"), draft("1.2.1", "1.2.0"))

        assertThat(bar(drafts, listOf(release("1.2.0"))).draftTitle).isEqualTo("Next version")
        assertThat(bar(drafts, listOf(release("1.2.0")), CatalogSegment(letters, "1.2.1")).draftTitle).isEqualTo("Draft v1.2.1")
    }

    @Test
    fun `an in-between version starts from the latest published version, once`() {
        val published = listOf(release("1.2.0"), release("1.1.0"))

        val none = bar(listOf(draft("1.2.1", "1.2.0")), published)
        assertThat(none.offerInBetween).isTrue()
        assertThat(none.latestPublished).isEqualTo("1.2.0")

        val open = bar(listOf(draft("1.3.0", "1.2.0"), draft("1.2.1", "1.2.0")), published)
        assertThat(open.offerInBetween).`as`("one is open: continue it").isFalse()
        assertThat(open.openInBetween).isEqualTo("1.2.1")
    }

    @Test
    fun `a fix of an older version published after the next version started is named, once, and not its base`() {
        val earlier = OffsetDateTime.parse("2026-10-01T10:00:00Z")
        val base = CatalogVersion("1.1.0", CatalogVersionStatus.RELEASED, releasedAt = earlier, contentRetained = true)
        val olderFix = CatalogVersion("1.0.1", CatalogVersionStatus.RELEASED, releasedAt = earlier.plusDays(2), contentRetained = true)
        val older = CatalogVersion("1.0.0", CatalogVersionStatus.RELEASED, releasedAt = earlier.minusDays(5), contentRetained = true)

        val bar = bar(listOf(draft("1.1.1", "1.1.0")), listOf(base, olderFix, older))

        assertThat(bar.publishedSince.map { it.version }).containsExactly("1.0.1")
        assertThat(bar.newerPatchOf("1.0.0")).`as`("why 1.0.0 cannot be fixed").isEqualTo("1.0.1")
        assertThat(bar.newerPatchOf("1.1.0")).isNull()
        assertThat(bar(listOf(draft("1.1.1", "1.1.0"), draft("1.0.2", "1.0.1")), listOf(base, olderFix, older), CatalogSegment(letters, "1.0.2")).publishedSince)
            .`as`("only the next version is told").isEmpty()
    }

    @Test
    fun `no in-between version without a published version, the right to edit, or its content`() {
        assertThat(bar(listOf(draft("1.0.0", null)), emptyList()).offerInBetween).isFalse()
        assertThat(bar(listOf(draft("1.2.1", "1.2.0")), listOf(release("1.2.0")), mayEdit = false).offerInBetween).isFalse()

        val notKept = bar(listOf(draft("1.2.1", "1.2.0")), listOf(release("1.2.0", retained = false)))
        assertThat(notKept.offerInBetween).isFalse()
        assertThat(notKept.inBetweenWithheldNoContent).`as`("said why").isTrue()
    }

    @Test
    fun `a draft can be discarded by a publisher, a published version deleted by a catalog manager, and the page says what is missing`() {
        val drafts = listOf(draft("1.3.0", "1.2.0"))
        val releases = listOf(release("1.2.0"))

        val publisher = bar(drafts, releases).copy(mayDeleteReleases = false)
        assertThat(publisher.versionRows.map { it.version to it.offerRemove }).containsExactly("1.3.0" to true, "1.2.0" to false)
        assertThat(publisher.removalWithheld).isTrue()

        val manager = bar(drafts, releases).copy(mayDeleteReleases = true)
        assertThat(manager.versionRows.all { it.offerRemove }).isTrue()
        assertThat(manager.removalWithheld).isFalse()
        assertThat(manager.versionRows.map { it.removeAction }).containsExactly(
            "/tenants/acme/catalogs/letters/drafts/discard",
            "/tenants/acme/catalogs/letters/releases/1.2.0/delete",
        )
    }
}
