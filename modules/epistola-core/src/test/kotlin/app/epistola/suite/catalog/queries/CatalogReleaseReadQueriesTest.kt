// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.catalog.revisions.ReleaseContentHashBackfill
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.recordBeforeContentHashes
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The queries the release list and the release detail page read from.
 *
 * Both arrived covered only by markup assertions, which cannot see what they actually answer: the
 * release list reads in SemVer order whether the SQL sorted it or the rows happened to come back that
 * way, and the order is what makes "latest" mean anything -- so it is pinned here, once.
 */
class CatalogReleaseReadQueriesTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var backfill: ReleaseContentHashBackfill

    // ── GetCatalogReleaseDetail ─────────────────────────────────────────────────

    /**
     * The detail page's whole reason for existing: a release carries the catalog's details as they
     * were when it was cut, so one made under an old name still says so. Read from the working copy
     * this would silently show today's name against a year-old release.
     */
    @Test
    fun `a release reports the catalog details it was cut with, not today's`() {
        val tenant = createTenant("Release Detail Frozen")
        val key = CatalogKey.of("frozen-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Original name").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant.id))), name = "Brand").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogDraft(tenantKey = tenant.id, catalogKey = key, version = "1.0.0", notes = "first").execute()

            UpdateCatalogMetadata(
                tenantKey = tenant.id,
                catalogKey = key,
                name = "Renamed since",
                description = "and described since",
            ).execute()

            val release = GetCatalogReleaseDetail(tenant.id, key, "1.0.0").query()!!

            assertThat(release.catalog.name).isEqualTo("Original name")
            assertThat(release.notes).isEqualTo("first")
            assertThat(release.resources.map { it.resourceKey }).contains("brand")
            // Twelve characters, as git shows a short hash: enough to compare two by eye.
            assertThat(release.shortFingerprint).isEqualTo(release.fingerprint.take(12)).hasSize(12)
        }
    }

    @Test
    fun `a version that was never released is nothing, and another catalog's release is not it`() {
        val tenant = createTenant("Release Detail Missing")
        val mine = CatalogKey.of("mine-cat")
        val other = CatalogKey.of("other-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = mine, name = "Mine").execute()
            CreateCatalog(tenantKey = tenant.id, id = other, name = "Other").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(other, TenantId(tenant.id))), name = "Brand").execute()
            MarkCatalogReady(tenant.id, other).execute()
            ReleaseCatalogDraft(tenantKey = tenant.id, catalogKey = other, version = "1.0.0").execute()

            assertThat(GetCatalogReleaseDetail(tenant.id, mine, "9.9.9").query())
                .`as`("a typed version that names nothing")
                .isNull()
            assertThat(GetCatalogReleaseDetail(tenant.id, mine, "1.0.0").query())
                .`as`("the catalog key is part of the identity, so a sibling's release is not this one's")
                .isNull()
        }
    }

    /**
     * What identifies a release (ADR 0034): its content hash, which a release cut now has as its
     * fingerprint. One recorded before content hashes keeps its older fingerprint and has no content
     * hash until the backfill gives it one; the page shows both then, and says why.
     */
    @Test
    fun `a release reports its content hash, and whether it was recorded before content hashes`() {
        val tenant = createTenant("Release Detail Content Hash")
        val key = CatalogKey.of("hashed-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Hashed").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant.id))), name = "Brand").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogDraft(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()
        }
        val cut = withMediator { GetCatalogReleaseDetail(tenant.id, key, "1.0.0").query()!! }
        assertThat(cut.contentHash).startsWith("v6:").isEqualTo(cut.fingerprint)
        assertThat(cut.shortContentHash).isEqualTo(cut.contentHash!!.take(12))
        assertThat(cut.recordedBeforeContentHashes).isFalse()

        val v5 = withMediator { mediator.recordBeforeContentHashes(jdbi, tenant.id, key, "1.0.0") }
        val recordedBefore = withMediator { GetCatalogReleaseDetail(tenant.id, key, "1.0.0").query()!! }
        assertThat(recordedBefore.fingerprint).isEqualTo(v5).startsWith("v5:")
        assertThat(recordedBefore.contentHash).`as`("being computed").isNull()
        assertThat(recordedBefore.shortContentHash).isNull()
        assertThat(recordedBefore.recordedBeforeContentHashes).isTrue()

        backfill.run()

        val given = withMediator { GetCatalogReleaseDetail(tenant.id, key, "1.0.0").query()!! }
        assertThat(given.contentHash).`as`("given the content hash of what it retained").isEqualTo(cut.contentHash)
        assertThat(given.fingerprint).isEqualTo(v5)
        assertThat(given.recordedBeforeContentHashes).isTrue()
    }

    // ── ListCatalogReleaseSummaries ───────────────────────────────────────────────

    /**
     * Newest first by version components, not as text: 1.10.0 is newer than 1.9.0, which sorting
     * the strings gets backwards. Asserted on the list, because the page test could only compare
     * where two version strings appeared in the markup — a proxy that passes by coincidence.
     */
    @Test
    fun `releases come back newest first, by version and not by text`() {
        val tenant = createTenant("Release List Order")
        val key = CatalogKey.of("order-cat")
        val catalogId = CatalogId(key, TenantId(tenant.id))
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Order cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("one"), catalogId), name = "One").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogDraft(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("two"), catalogId), name = "Two").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogDraft(tenantKey = tenant.id, catalogKey = key, version = "1.9.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("three"), catalogId), name = "Three").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogDraft(tenantKey = tenant.id, catalogKey = key, version = "1.10.0").execute()

            assertThat(ListCatalogReleaseSummaries(tenant.id, key).query().map { it.version })
                .containsExactly("1.10.0", "1.9.0", "1.0.0")
        }
    }

    @Test
    fun `a release counts its resources`() {
        val tenant = createTenant("Release List Counts")
        val kept = CatalogKey.of("kept-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = kept, name = "Kept").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("alpha"), CatalogId(kept, TenantId(tenant.id))), name = "Alpha").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("beta"), CatalogId(kept, TenantId(tenant.id))), name = "Beta").execute()
            MarkCatalogReady(tenant.id, kept).execute()
            ReleaseCatalogDraft(tenantKey = tenant.id, catalogKey = kept, version = "1.0.0").execute()

            assertThat(ListCatalogReleaseSummaries(tenant.id, kept).query().single().resourceCount).isEqualTo(2)
        }
    }

    @Test
    fun `a catalog that has never released lists nothing`() {
        val tenant = createTenant("Release List Empty")
        val key = CatalogKey.of("empty-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Empty cat").execute()

            assertThat(ListCatalogReleaseSummaries(tenant.id, key).query()).isEmpty()
        }
    }
}
