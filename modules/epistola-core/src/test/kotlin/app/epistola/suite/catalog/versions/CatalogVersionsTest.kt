// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogVersion
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A catalog consists of versions (contract ADR 0002, amendment 2): released ones and, for an authored
 * catalog, its next version (and any parallel work, [SeveralDraftsTest]). A draft has no number until
 * it is released; releasing the next version continues it on the release.
 */
class CatalogVersionsTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun catalog(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        tenant
    }

    private fun versions(tenant: TenantKey) = withMediator { ListCatalogVersions(tenant, letters).query() }.map { it.label to it.status }

    @Test
    fun `a new catalog has a next version without a number, and releasing it numbers it and continues it on the release`() {
        val tenant = catalog("versions-draft")
        assertThat(versions(tenant)).containsExactly(CatalogDraft.NEXT_LABEL to CatalogVersionStatus.DRAFT)
        val next = withMediator { GetCatalogDraft(tenant, letters).query()!! }
        assertThat(next.version).isNull()
        assertThat(next.isNext).isTrue()
        assertThat(next.base).isNull()

        withMediator { mediator.releaseNext(CatalogId(letters, TenantId(tenant))) }

        assertThat(versions(tenant)).containsExactly(CatalogDraft.NEXT_LABEL to CatalogVersionStatus.DRAFT, "v1.0.0" to CatalogVersionStatus.RELEASED)
        val continued = withMediator { GetCatalogDraft(tenant, letters).query()!! }
        val release = withMediator { GetCatalogVersion(tenant, letters, "1.0.0").query()!! }
        assertThat(release.versionId).`as`("the release keeps the draft's id").isEqualTo(next.versionId)
        assertThat(continued.versionId).`as`("the next version continues under a new one").isNotEqualTo(next.versionId)
        assertThat(withMediator { GetCatalogVersion(tenant, letters, next.versionId).query()!! }.version).`as`("addressed by its id").isEqualTo("1.0.0")
        assertThat(continued.base).isEqualTo("1.0.0")
        assertThat(withMediator { GetCatalogVersion(tenant, letters, "1.0.0").query()!! }.status).isEqualTo(CatalogVersionStatus.RELEASED)
        assertThat(withMediator { GetCatalogVersion(tenant, letters, "1.0.1").query() }).`as`("a draft is not found by a number").isNull()
    }

    @Test
    fun `a new tenant's default catalog starts with a next version`() {
        val tenant = withMediator { createTenant("versions-default").id }

        val versions = withMediator { ListCatalogVersions(tenant, CatalogKey.DEFAULT).query() }

        assertThat(versions.map { it.label to it.status }).containsExactly(CatalogDraft.NEXT_LABEL to CatalogVersionStatus.DRAFT)
    }

    @Test
    fun `an address resolves a catalog by its name, a draft by next or its id, and a release by its number`() {
        val tenant = catalog("versions-resolve")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(letters, TenantId(tenant))), "Brand").execute()
            mediator.releaseNext(CatalogId(letters, TenantId(tenant)))
            UpdateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(letters, TenantId(tenant))), name = "Brand, renamed").execute()
        }
        val namespace = withMediator { GetCatalog(tenant, letters).query()!! }.namespace

        val draft = withMediator { ResolveCatalogVersion(tenant, "~", namespace, letters, "next").query() }
        assertThat(draft.isDraft).isTrue()
        assertThat(draft.version.isNext).isTrue()
        val byId = withMediator { ResolveCatalogVersion(tenant, "~", namespace, letters, draft.version.versionId).query() }
        assertThat(byId.version.versionId).isEqualTo(draft.version.versionId)
        val released = withMediator { ResolveCatalogVersion(tenant, null, namespace, letters, "1.0.0").query() }
        assertThatThrownBy { released.requireDraft() }.isInstanceOf(CatalogVersionReleasedException::class.java)

        // A release answers with what it holds, not with the working copy.
        val theme = withMediator { GetReleasedResource(tenant, letters, "1.0.0", "theme", "brand").query() } as ThemeResource
        assertThat(theme.name).isEqualTo("Brand")
        assertThat(withMediator { ListReleasedResources(tenant, letters, "1.0.0", "theme").query() }.map { it.key }).containsExactly("brand")

        assertThatThrownBy { withMediator { ResolveCatalogVersion(tenant, null, "elsewhere", letters, "1.0.0").query() } }
            .isInstanceOf(CatalogNotFoundException::class.java)
        assertThatThrownBy { withMediator { ResolveCatalogVersion(tenant, null, namespace, letters, "9.9.9").query() } }
            .isInstanceOf(CatalogVersionNotFoundException::class.java)
        assertThatThrownBy { withMediator { ResolveCatalogVersion(tenant, null, namespace, letters, "1.0.1").query() } }
            .`as`("the number the next version would get is not its address").isInstanceOf(CatalogVersionNotFoundException::class.java)
        assertThatThrownBy { withMediator { ResolveCatalogVersion(tenant, null, namespace, letters, UUID.randomUUID().toString()).query() } }
            .isInstanceOf(CatalogVersionNotFoundException::class.java)
    }

    @Test
    fun `resolving an address needs membership only, so an author can write without CATALOG_VIEW`() {
        val tenant = catalog("versions-membership")
        val namespace = withMediator { GetCatalog(tenant, letters).query()!! }.namespace
        fun member(of: TenantKey, roles: Set<TenantRole>) = UUID.randomUUID().let { id ->
            EpistolaPrincipal(
                userId = UserKey.of(id),
                externalId = "apikey:$id",
                email = "apikey-$id@npa.epistola",
                displayName = "Key",
                tenantMemberships = mapOf(of to roles),
                globalRoles = emptySet(),
                platformRoles = emptySet(),
                currentTenantId = of,
            )
        }

        val resolved = runAs(member(tenant, setOf(TenantRole.CONTENT_AUTHOR))) { ResolveCatalogVersion(tenant, null, namespace, letters, "next").query() }
        assertThat(resolved.isDraft).isTrue()

        val other = createTenant("versions-outsider").id
        assertThatThrownBy { runAs(member(other, TenantRole.entries.toSet())) { ResolveCatalogVersion(tenant, null, namespace, letters, "next").query() } }
            .isNotInstanceOf(CatalogVersionNotFoundException::class.java)
    }

    @Test
    fun `an author starts and renames parallel work, and only a publisher releases, discards and deploys (contract 2_0)`() {
        val tenant = catalog("versions-permissions")
        withMediator { mediator.releaseNext(CatalogId(letters, TenantId(tenant))) }
        withMediator { CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), "Production").execute() }
        fun member(roles: Set<TenantRole>) = UUID.randomUUID().let { id ->
            EpistolaPrincipal(
                userId = UserKey.of(id),
                externalId = "apikey:$id",
                email = "apikey-$id@npa.epistola",
                displayName = "Key",
                tenantMemberships = mapOf(tenant to roles),
                globalRoles = emptySet(),
                platformRoles = emptySet(),
                currentTenantId = tenant,
            )
        }
        val author = member(setOf(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR))
        val publisher = member(setOf(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_PUBLISHER))

        val work = runAs(author) {
            val created = CreateCatalogDraft(tenant, letters, "Redesign").execute()
            RenameCatalogDraft(tenant, letters, DraftRef.Id(created.versionId), "Redesign 2027").execute()
        }
        val workRef = DraftRef.Id(work.versionId)
        assertThatThrownBy { runAs(author) { DeleteCatalogDraft(tenant, letters, workRef).execute() } }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { runAs(author) { ReleaseCatalogVersion(tenant, letters).execute() } }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { runAs(author) { DeployCatalogVersion(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), letters, "1.0.0").execute() } }
            .isInstanceOf(PermissionDeniedException::class.java)

        runAs(publisher) {
            DeleteCatalogDraft(tenant, letters, workRef).execute()
            DeployCatalogVersion(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), letters, "1.0.0").execute()
        }
        assertThat(versions(tenant).map { it.first }).doesNotContain("Redesign 2027")
    }
}
