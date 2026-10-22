// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.releases.AssessDraftNumber
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.CatalogPreset
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
import app.epistola.suite.catalog.releases.SetCatalogPresets
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
 * The next release has changes when its content differs from the release it is based on (ADR 0034):
 * its resources and what the catalog holds beside them, the content hash covers — its dependencies,
 * default theme, presets and license. Its presentation (name, description, keywords) is not content.
 */
class CatalogSettingChangesTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    @Test
    fun `a dependency added is a change of the next release, and needs a minor`() {
        val tenant = released("settings-dep-added")
        withMediator {
            declareDependency(tenant, letters, shared, "1.0.0")

            val changes = GetCatalogResourceChanges(tenant, letters).query()

            assertThat(changes.hasUnreleasedChanges).isTrue()
            assertThat(changes.settings).containsExactly(
                CatalogSettingChange("catalogDependency/shared", CatalogResourceState.NEW, "Shared", lock = DependencyLock("1.0.0")),
            )
            assertThat(changes.notReady).`as`("a catalog setting needs no ready mark").isEmpty()
            assertThat(AssessDraftNumber(tenant, letters).query()!!.suggested).isEqualTo("1.1.0")
        }
    }

    @Test
    fun `a dependency removed is a change`() {
        val tenant = released("settings-dep-removed", dependsOnShared = true)
        withMediator {
            UpdateCatalogDependencies(tenant, letters, emptyList()).execute()

            val changes = GetCatalogResourceChanges(tenant, letters).query()

            assertThat(changes.hasUnreleasedChanges).isTrue()
            assertThat(changes.settings).containsExactly(
                CatalogSettingChange("catalogDependency/shared", CatalogResourceState.REMOVED, "Shared", baseLock = DependencyLock("1.0.0")),
            )
        }
    }

    @Test
    fun `a dependency locked to another release is a change`() {
        val tenant = released("settings-dep-repinned", dependsOnShared = true)
        withMediator {
            declareDependency(tenant, letters, shared, "1.1.0")

            val changes = GetCatalogResourceChanges(tenant, letters).query()

            assertThat(changes.hasUnreleasedChanges).isTrue()
            assertThat(changes.settings).containsExactly(
                CatalogSettingChange("catalogDependency/shared", CatalogResourceState.MODIFIED, "Shared", lock = DependencyLock("1.1.0"), baseLock = DependencyLock("1.0.0")),
            )

            declareDependency(tenant, letters, shared, "1.0.0")
            assertThat(GetCatalogResourceChanges(tenant, letters).query().hasUnreleasedChanges).`as`("locked back to the release's").isFalse()
        }
    }

    /**
     * Naming a dependency's draft pins that draft's base when it has nothing to publish, so it is a
     * change of this catalog only when that base is another release; what the draft changed itself
     * is the other catalog's change, published along.
     */
    @Test
    fun `a dependency naming the other catalog's draft is compared as the release that draft is based on`() {
        val tenant = released("settings-named-draft", dependsOnShared = true)
        withMediator {
            val sharedDraft = GetCatalogDraft(tenant, shared).query()!!
            assertThat(sharedDraft.base).isEqualTo("1.1.0")
            nameDraft(tenant, sharedDraft.versionId)

            val moved = GetCatalogResourceChanges(tenant, letters).query().settings.single()
            assertThat(moved.state).isEqualTo(CatalogResourceState.MODIFIED)
            assertThat(moved.lock?.releaseVersion).isNull()
            assertThat(moved.lock?.draftName).isNotNull()
            assertThat(moved.baseLock).isEqualTo(DependencyLock("1.0.0"))

            declareDependency(tenant, letters, shared, "1.1.0")
            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters, version = "1.0.1").execute()
            nameDraft(tenant, sharedDraft.versionId)

            assertThat(GetCatalogResourceChanges(tenant, letters).query().hasUnreleasedChanges)
                .`as`("the draft named is based on the release pinned").isFalse()
        }
    }

    @Test
    fun `another default theme is a change, numbered as a fix`() {
        val tenant = released("settings-default-theme")
        withMediator {
            SetCatalogDefaultTheme(tenant, letters, CatalogDefaultTheme(ThemeKey.of("brand"), letters)).execute()

            val changes = GetCatalogResourceChanges(tenant, letters).query()

            assertThat(changes.hasUnreleasedChanges).isTrue()
            assertThat(changes.settings.map { it.entry to it.name }).containsExactly("catalog/defaultTheme" to "Default theme")
            assertThat(AssessDraftNumber(tenant, letters).query()!!.needs).isEqualTo(ChangeKind.NONE)
        }
    }

    @Test
    fun `a preset added is a change that needs a minor, one removed a major`() {
        val tenant = released("settings-presets")
        withMediator {
            SetCatalogPresets(tenant, letters, listOf(CatalogPreset("formal", "Formal"))).execute()

            assertThat(GetCatalogResourceChanges(tenant, letters).query().settings)
                .containsExactly(CatalogSettingChange("catalog/presets", CatalogResourceState.NEW, "Presets"))
            assertThat(AssessDraftNumber(tenant, letters).query()!!.needs).isEqualTo(ChangeKind.ADDITIVE)

            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters, version = "1.1.0").execute()
            SetCatalogPresets(tenant, letters, emptyList()).execute()

            assertThat(AssessDraftNumber(tenant, letters).query()!!.needs).isEqualTo(ChangeKind.BREAKING)
        }
    }

    @Test
    fun `another license is a change`() {
        val tenant = released("settings-license")
        withMediator {
            UpdateCatalogMetadata(tenant, letters, name = "Letters", description = null, license = CatalogLicense("Apache License 2.0", "Apache-2.0")).execute()

            assertThat(GetCatalogResourceChanges(tenant, letters).query().settings)
                .containsExactly(CatalogSettingChange("catalog/license", CatalogResourceState.NEW, "License"))
        }
    }

    @Test
    fun `the catalog's presentation is not a change of the release`() {
        val tenant = released("settings-presentation")
        withMediator {
            UpdateCatalogMetadata(tenant, letters, name = "Letters, renamed", description = "Described now", keywords = setOf("letters")).execute()

            val changes = GetCatalogResourceChanges(tenant, letters).query()

            assertThat(changes.hasUnreleasedChanges).isFalse()
            assertThat(changes.settings).isEmpty()
        }
    }

    @Test
    fun `a draft whose only change is a dependency publishes, as a release with other content`() {
        val tenant = released("settings-publish")
        withMediator {
            declareDependency(tenant, letters, shared, "1.0.0")
            assertThat(GetCatalogResourceChanges(tenant, letters).query().hasUnreleasedChanges).isTrue()

            val release = ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters).execute()

            assertThat(release.version).isEqualTo("1.1.0")
            assertThat(release.unchangedContent).isFalse()
            assertThat(contentHash(tenant, letters, "1.1.0")).isNotEqualTo(contentHash(tenant, letters, "1.0.0"))
            assertThat(GetCatalogResourceChanges(tenant, letters).query().hasUnreleasedChanges).isFalse()
        }
    }

    @Test
    fun `a dependency's draft whose only change is its default theme is published along, not pinned at its base`() {
        val tenant = released("settings-along")
        withMediator {
            val sharedDraft = GetCatalogDraft(tenant, shared).query()!!
            UpdateCatalogDependencies(tenant, letters, listOf(DeclaredCatalogDependency(registryOf(tenant, shared), namespaceOf(tenant, shared), shared.value, versionId = sharedDraft.versionId))).execute()
            SetCatalogDefaultTheme(tenant, shared, CatalogDefaultTheme(ThemeKey.of("base"), shared)).execute()

            val release = ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters, releaseDependencies = true).execute()

            val pinned = ListReleaseDependencies(tenant, letters, release.version).query().single()
            assertThat(pinned.releaseVersion).`as`("shared's default theme was released with it").isEqualTo("1.1.1")
        }
    }

    /**
     * Shared released as 1.0.0 and 1.1.0; Letters, with one theme and optionally depending on Shared
     * 1.0.0, released as 1.0.0. Returns the tenant.
     */
    private fun released(name: String, dependsOnShared: Boolean = false): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            CreateCatalog(tenantKey = tenant, id = shared, name = "Shared").execute()
            val sharedId = CatalogId(shared, TenantId(tenant))
            CreateTheme(id = ThemeId(ThemeKey.of("base"), sharedId), name = "Base").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = shared, version = "1.0.0").execute()
            UpdateTheme(id = ThemeId(ThemeKey.of("base"), sharedId), name = "Base, again").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = shared, version = "1.1.0").execute()

            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(letters, TenantId(tenant))), name = "Brand").execute()
            if (dependsOnShared) declareDependency(tenant, letters, shared, "1.0.0")
            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters, version = "1.0.0").execute()
            assertThat(GetCatalogResourceChanges(tenant, letters).query().hasUnreleasedChanges).`as`("released as it is").isFalse()
        }
        return tenant
    }

    /** Letters declares Shared by naming its draft [versionId], not a release. */
    private fun nameDraft(tenant: TenantKey, versionId: String) = UpdateCatalogDependencies(
        tenant,
        letters,
        listOf(DeclaredCatalogDependency(registryOf(tenant, shared), namespaceOf(tenant, shared), shared.value, versionId = versionId)),
    ).execute()

    private fun contentHash(tenant: TenantKey, catalog: CatalogKey, version: String): String = withMediator {
        requireNotNull(GetCatalogReleaseDetail(tenant, catalog, version).query()?.contentHash)
    }

    private fun registryOf(tenant: TenantKey, catalog: CatalogKey) = withMediator { GetCatalog(tenant, catalog).query()!!.registry }

    private fun namespaceOf(tenant: TenantKey, catalog: CatalogKey) = withMediator { GetCatalog(tenant, catalog).query()!!.namespace }
}
