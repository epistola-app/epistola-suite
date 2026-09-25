// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.catalog.revisions.forgetRetainedContent
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The three queries the release list and the release detail page read from.
 *
 * All three arrived covered only by markup assertions, which cannot see what they actually answer:
 * a page shows "not kept" whether `resourceCount` is null or zero, and reads the release list in
 * SemVer order whether the SQL sorted it or the rows happened to come back that way. Each of those
 * is a rule with consequences — `resourceCount == null` is what withholds the export, and the order
 * is what makes "latest" mean anything — so each is pinned here, once, in one place.
 *
 * Together in one class because they are one read model split across three entry points: the same
 * `content_retained` fact and the same `LATEST_RELEASE_ORDER` decide all of them, and a change to
 * either should fail next to its siblings rather than in whichever file happened to cover it.
 */
class CatalogReleaseReadQueriesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    // ── GetCatalogRelease ─────────────────────────────────────────────────

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
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0", notes = "first").execute()

            UpdateCatalogMetadata(
                tenantKey = tenant.id,
                catalogKey = key,
                name = "Renamed since",
                description = "and described since",
            ).execute()

            val release = GetCatalogRelease(tenant.id, key, "1.0.0").query()!!

            assertThat(release.catalog.name).isEqualTo("Original name")
            assertThat(release.notes).isEqualTo("first")
            assertThat(release.retained).isTrue()
            assertThat(release.resources.map { it.resourceKey }).contains("brand")
            // Twelve characters, as git shows a short hash: enough to compare two by eye.
            assertThat(release.shortFingerprint).isEqualTo(release.fingerprint.take(12)).hasSize(12)
        }
    }

    /**
     * A release cut before releases kept their content. It still has details — the manifest snapshot
     * was always stored — and must report no resources while saying why, because a page that reads
     * an empty list as "this release was empty" states something false.
     */
    @Test
    fun `a release that kept no content still has details and lists nothing`() {
        val tenant = createTenant("Release Detail Legacy")
        val key = CatalogKey.of("legacy-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Legacy cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant.id))), name = "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()
        }
        assertThat(jdbi.forgetRetainedContent(tenant.id, key)).isEqualTo(1)

        withMediator {
            val release = GetCatalogRelease(tenant.id, key, "1.0.0").query()!!

            assertThat(release.retained).isFalse()
            assertThat(release.resources).isEmpty()
            assertThat(release.catalog.name).`as`("the snapshot survives, so the details do").isEqualTo("Legacy cat")
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
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = other, version = "1.0.0").execute()

            assertThat(GetCatalogRelease(tenant.id, mine, "9.9.9").query())
                .`as`("a typed version that names nothing")
                .isNull()
            assertThat(GetCatalogRelease(tenant.id, mine, "1.0.0").query())
                .`as`("the catalog key is part of the identity, so a sibling's release is not this one's")
                .isNull()
        }
    }

    // ── ListCatalogReleases ───────────────────────────────────────────────

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
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("two"), catalogId), name = "Two").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.9.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("three"), catalogId), name = "Three").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.10.0").execute()

            assertThat(ListCatalogReleases(tenant.id, key).query().map { it.version })
                .containsExactly("1.10.0", "1.9.0", "1.0.0")
        }
    }

    /**
     * `resourceCount` is null, not zero, for a release that kept nothing — the two mean different
     * things and the page renders them differently, but a markup assertion cannot tell them apart.
     * Counting entries could not answer this either: a catalog with no resources retains a release
     * that contains nothing, which is why the release carries the flag.
     */
    @Test
    fun `a retained release counts its resources and one that kept nothing counts none at all`() {
        val tenant = createTenant("Release List Counts")
        val kept = CatalogKey.of("kept-cat")
        val forgotten = CatalogKey.of("forgotten-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = kept, name = "Kept").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("alpha"), CatalogId(kept, TenantId(tenant.id))), name = "Alpha").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("beta"), CatalogId(kept, TenantId(tenant.id))), name = "Beta").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = kept, version = "1.0.0").execute()

            CreateCatalog(tenantKey = tenant.id, id = forgotten, name = "Forgotten").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("alpha"), CatalogId(forgotten, TenantId(tenant.id))), name = "Alpha").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = forgotten, version = "1.0.0").execute()
        }
        jdbi.forgetRetainedContent(tenant.id, forgotten)

        withMediator {
            val keptRelease = ListCatalogReleases(tenant.id, kept).query().single()
            assertThat(keptRelease.retained).isTrue()
            assertThat(keptRelease.resourceCount).isEqualTo(2)

            val forgottenRelease = ListCatalogReleases(tenant.id, forgotten).query().single()
            assertThat(forgottenRelease.retained).isFalse()
            assertThat(forgottenRelease.resourceCount)
                .`as`("null is not zero: nothing was counted, rather than nothing being there")
                .isNull()
        }
    }

    @Test
    fun `a catalog that has never released lists nothing`() {
        val tenant = createTenant("Release List Empty")
        val key = CatalogKey.of("empty-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Empty cat").execute()

            assertThat(ListCatalogReleases(tenant.id, key).query()).isEmpty()
        }
    }

    // ── ListRetainedReleases ──────────────────────────────────────────────

    /**
     * The single definition of "which releases can be handed back exactly as released", so the offer
     * to export one and the export itself cannot disagree. A release that kept nothing is excluded,
     * which is the whole predicate; the order matches the release list's, so both screens read
     * "latest" the same way.
     */
    @Test
    fun `only releases that kept their content can be handed back, newest first`() {
        val tenant = createTenant("Retained Releases")
        val key = CatalogKey.of("retained-cat")
        val catalogId = CatalogId(key, TenantId(tenant.id))
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Retained cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("one"), catalogId), name = "One").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.0.0").execute()
        }
        // The first release is made to look like one cut before content was kept; the later ones
        // are real. Forgetting is per catalog, so the order matters: release, forget, release again.
        jdbi.forgetRetainedContent(tenant.id, key)

        withMediator {
            CreateTheme(id = ThemeId(ThemeKey.of("two"), catalogId), name = "Two").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.9.0").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("three"), catalogId), name = "Three").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = key, version = "1.10.0").execute()

            assertThat(ListRetainedReleases(tenant.id, key).query())
                .`as`("1.0.0 kept nothing, so it cannot be handed back")
                .containsExactly("1.10.0", "1.9.0")

            // The same fact the list reports, from the other query: one predicate, two entry points.
            val retainedByList = ListCatalogReleases(tenant.id, key).query().filter { it.retained }.map { it.version }
            assertThat(ListRetainedReleases(tenant.id, key).query()).isEqualTo(retainedByList)
        }
    }

    @Test
    fun `a catalog with no retained release offers nothing to hand back`() {
        val tenant = createTenant("Retained Releases None")
        val key = CatalogKey.of("none-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "None cat").execute()

            assertThat(retained(tenant.id, key)).isEmpty()
        }
    }

    private fun retained(tenantKey: TenantKey, catalogKey: CatalogKey) = ListRetainedReleases(tenantKey, catalogKey).query()
}
