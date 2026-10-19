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
import java.util.UUID

/**
 * The catalog page's view of an authored catalog's versions (docs/catalog-working-model.md, contract
 * ADR 0002 amendment 2): the next version, long-term work next to it, and the published versions.
 */
class CatalogDraftBarVersionsTest {

    private val letters = CatalogKey.of("letters")
    private val redesignId = UUID.fromString("0193a6b2-7f4e-7c1d-9a2b-3c4d5e6f7a8b")
    private val nextId = UUID.fromString("0193a6b2-7f4e-7c1d-9a2b-000000000001")

    private fun next(base: String?) = CatalogVersion(null, CatalogVersionStatus.DRAFT, base = base, draftId = nextId, isNext = true)

    private fun work(name: String, base: String?, id: UUID = redesignId) = CatalogVersion(null, CatalogVersionStatus.DRAFT, base = base, draftId = id, name = name)

    private fun release(version: String, liveIn: List<String> = emptyList(), base: String? = null) = CatalogVersion(
        version,
        CatalogVersionStatus.RELEASED,
        base = base,
        releasedAt = OffsetDateTime.parse("2026-10-01T10:00:00Z"),
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
        chosen = segment.draft?.let { id -> drafts.first { it.draftId == id } } ?: drafts.firstOrNull { it.isNext },
        mayOpenDrafts = mayEdit,
        mayDiscardDrafts = true,
        pageAt = { "/tenants/acme/catalogs/${it.value}/draft" },
    )

    @Test
    fun `versions list the next version first, then long-term work, then published versions newest first, each to its own page`() {
        val rows = bar(
            drafts = listOf(next("1.2.0"), work("Redesign", "1.2.0")),
            releases = listOf(release("1.2.0", liveIn = listOf("production")), release("1.1.0")),
        ).versionRows

        assertThat(rows.map { Triple(it.label, it.next, it.draft) }).containsExactly(
            Triple("Next version", true, true),
            Triple("Redesign", false, true),
            Triple("v1.2.0", false, false),
            Triple("v1.1.0", false, false),
        )
        assertThat(rows.map { it.version }).`as`("a draft has no number").containsExactly(null, null, "1.2.0", "1.1.0")
        assertThat(rows.map { it.href }).containsExactly(
            "/tenants/acme/catalogs/letters/draft",
            "/tenants/acme/catalogs/letters@$redesignId/draft",
            "/tenants/acme/catalogs/letters/releases/1.2.0",
            "/tenants/acme/catalogs/letters/releases/1.1.0",
        )
        assertThat(rows[2].liveIn).containsExactly("production")
    }

    @Test
    fun `every version says the published version it started from, a published one too`() {
        val rows = bar(
            drafts = listOf(next("1.2.0")),
            releases = listOf(release("1.2.0", base = "1.1.0"), release("1.1.0")),
        ).versionRows

        assertThat(rows.map { it.label to it.base }).containsExactly("Next version" to "1.2.0", "v1.2.0" to "1.1.0", "v1.1.0" to null)
    }

    @Test
    fun `the next version is called so, long-term work by its name, and only the next version is published`() {
        val drafts = listOf(next("1.2.0"), work("Redesign", "1.2.0"))

        val onNext = bar(drafts, listOf(release("1.2.0")))
        assertThat(onNext.draftTitle).isEqualTo("Next version")
        assertThat(onNext.isNext).isTrue()
        assertThat(onNext.onLongTermWork).isFalse()
        assertThat(onNext.offerRelease).isTrue()
        assertThat(onNext.longTermWork.map { it.label to it.href }).containsExactly("Redesign" to "/tenants/acme/catalogs/letters@$redesignId/draft")

        val onWork = bar(drafts, listOf(release("1.2.0")), CatalogSegment(letters, redesignId))
        assertThat(onWork.draftTitle).isEqualTo("Redesign")
        assertThat(onWork.onLongTermWork).isTrue()
        assertThat(onWork.offerRelease).`as`("long-term work is not published").isFalse()
    }

    @Test
    fun `long-term work starts from the next version or a published version, for an author`() {
        val releases = listOf(release("1.2.0"), release("1.1.0"))

        assertThat(bar(listOf(next("1.2.0")), releases).offerCreateDraft).isTrue()
        assertThat(bar(listOf(next("1.2.0")), releases).createFromOptions).containsExactly("1.2.0", "1.1.0")
        assertThat(bar(listOf(next("1.2.0")), releases, mayEdit = false).offerCreateDraft).isFalse()
        assertThat(bar(listOf(next("1.2.0")), releases, mayEdit = false).draftControlsWithheld).`as`("said why").isTrue()
    }

    @Test
    fun `a catalog without a next version offers to start it on the latest release`() {
        val none = bar(emptyList(), listOf(release("1.2.0")))
        assertThat(none.noDraft).isTrue()
        assertThat(none.offerOpenDraft).isTrue()
        assertThat(none.openDraftFrom).isEqualTo("1.2.0")
    }

    @Test
    fun `a draft can be discarded by a publisher, a published version deleted by a catalog manager, and the page says what is missing`() {
        val drafts = listOf(next("1.2.0"), work("Redesign", "1.2.0"))
        val releases = listOf(release("1.2.0"))

        val publisher = bar(drafts, releases).copy(mayDeleteReleases = false)
        assertThat(publisher.versionRows.map { it.label to it.offerRemove }).containsExactly("Next version" to true, "Redesign" to true, "v1.2.0" to false)
        assertThat(publisher.removalWithheld).isTrue()

        val manager = bar(drafts, releases).copy(mayDeleteReleases = true)
        assertThat(manager.versionRows.all { it.offerRemove }).isTrue()
        assertThat(manager.removalWithheld).isFalse()
        assertThat(manager.versionRows.map { it.removeAction }).containsExactly(
            "/tenants/acme/catalogs/letters/drafts/discard",
            "/tenants/acme/catalogs/letters@$redesignId/drafts/discard",
            "/tenants/acme/catalogs/letters/releases/1.2.0/delete",
        )
    }
}
