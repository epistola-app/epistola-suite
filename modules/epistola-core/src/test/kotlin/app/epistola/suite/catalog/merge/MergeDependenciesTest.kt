// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
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
import org.junit.jupiter.api.Test

/**
 * Each catalog a version uses is an entry of its own in a merge (ADR 0032): adding, removing or
 * re-pinning one is its own change, and only both sides changing the same catalog differently is a
 * conflict. `letters` 1.0.0 uses `shared` 1.0.0; a fix made from it is released as 1.0.1, and merged
 * into the next release, which went its own way meanwhile.
 */
class MergeDependenciesTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")
    private val other = CatalogKey.of("other")

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), CatalogId(catalog, TenantId(tenant)))

    /** Declares exactly [pins] (catalog to release) for [catalog], in the draft [version] names or the next release. */
    private fun declare(tenant: TenantKey, catalog: CatalogKey, pins: Map<CatalogKey, String>, version: CatalogReleaseRef? = null) {
        val declared = pins.map { (on, release) ->
            val target = GetCatalog(tenant, on).query()!!
            DeclaredCatalogDependency(target.registry, target.namespace, on.value, releaseVersion = release)
        }
        UpdateCatalogDependencies(tenant, catalog, declared, catalogVersion = version).execute()
    }

    private fun released(tenant: TenantKey, catalog: CatalogKey, vararg versions: String) {
        CreateCatalog(tenantKey = tenant, id = catalog, name = catalog.value).execute()
        CreateTheme(id = theme(tenant, catalog, "th${catalog.value.take(3)}"), name = "${catalog.value} 0").execute()
        versions.forEachIndexed { index, version ->
            if (index > 0) UpdateTheme(theme(tenant, catalog, "th${catalog.value.take(3)}"), name = "${catalog.value} $index").execute()
            MarkCatalogReady(tenant, catalog).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = catalog, version = version).execute()
        }
    }

    /** `letters` 1.0.0 on `shared` 1.0.0; the fix declares [fix] and is released as 1.0.1; the next release declares [next]. */
    private fun lines(name: String, fix: Map<CatalogKey, String>, next: Map<CatalogKey, String>): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            released(tenant, shared, "1.0.0", "1.1.0", "1.2.0")
            released(tenant, other, "1.0.0")
            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            CreateTheme(id = theme(tenant, letters, "brand"), name = "Brand").execute()
            declare(tenant, letters, mapOf(shared to "1.0.0"))
            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters, version = "1.0.0").execute()

            val fixRef = CatalogReleaseRef(tenant, letters, DraftRef.Id(CreateCatalogDraft(tenant, letters, "Fix", "1.0.0").execute().versionId))
            declare(tenant, letters, fix, fixRef)
            MarkCatalogReady(tenant, letters, catalogVersion = fixRef).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters, version = "1.0.1", catalogVersion = fixRef).execute()

            declare(tenant, letters, next)
        }
        return tenant
    }

    private fun pins(tenant: TenantKey): Map<String, String?> = ListCatalogDependencies(tenant, letters).query().associate { it.catalogKey to it.releaseVersion }

    @Test
    fun `a dependency the release re-pinned and one the draft added are two changes, taken and kept without a conflict`() {
        // A fix moves its pin (a fix: nothing it uses broke); adding a dependency would need a minor.
        val tenant = lines("Merge Dependencies Apart", fix = mapOf(shared to "1.1.0"), next = mapOf(shared to "1.0.0", other to "1.0.0"))
        withMediator {
            val preview = PreviewDraftMerge(tenant, letters, "1.0.1").query()

            assertThat(preview.resources.filter { it.isSetting }.associate { it.contentKey to it.outcome }).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "catalog/defaultTheme" to MergeOutcome.UNCHANGED,
                    "catalog/presets" to MergeOutcome.UNCHANGED,
                    "catalogDependency/other" to MergeOutcome.OURS,
                    "catalogDependency/shared" to MergeOutcome.THEIRS,
                ),
            )
            assertThat(preview.conflicts).isEmpty()

            val merged = MergeReleaseIntoDraft(tenant, letters, "1.0.1", expectedToken = preview.token).execute()

            assertThat(merged.taken).containsExactly("catalogDependency/shared")
            assertThat(pins(tenant)).isEqualTo(mapOf("shared" to "1.1.0", "other" to "1.0.0"))
        }
    }

    @Test
    fun `a dependency the release dropped is dropped, and one the draft added stays`() {
        val tenant = lines("Merge Dependencies Dropped", fix = emptyMap(), next = mapOf(shared to "1.0.0", other to "1.0.0"))
        withMediator {
            val merged = MergeReleaseIntoDraft(tenant, letters, "1.0.1").execute()

            assertThat(merged.removed).containsExactly("catalogDependency/shared")
            assertThat(pins(tenant)).isEqualTo(mapOf("other" to "1.0.0"))
        }
    }

    @Test
    fun `only both sides re-pinning the same catalog differently is a conflict`() {
        val tenant = lines("Merge Dependencies Clash", fix = mapOf(shared to "1.2.0"), next = mapOf(shared to "1.1.0"))
        withMediator {
            val conflict = PreviewDraftMerge(tenant, letters, "1.0.1").query().conflicts.single()
            assertThat(conflict.contentKey to (conflict.ours to conflict.theirs)).isEqualTo("catalogDependency/shared" to (MergeChange.MODIFIED to MergeChange.MODIFIED))
            assertThat(conflict.name).isEqualTo("shared")

            MergeReleaseIntoDraft(tenant, letters, "1.0.1", resolutions = mapOf("catalogDependency/shared" to MergeResolution.Theirs)).execute()

            assertThat(pins(tenant)).isEqualTo(mapOf("shared" to "1.2.0"))
        }
    }
}
