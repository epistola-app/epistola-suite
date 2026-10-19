// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.catalog.versions.GetCatalogDraft
import app.epistola.suite.catalog.versions.GetCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Any draft is released on its own (contract ADR 0002, "Releasing any draft"), and the release keeps
 * the draft's id; which draft is the next version is this suite's convention, moved without touching
 * either draft's content or base.
 */
class PromotionTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: TenantKey) = CatalogId(letters, TenantId(tenant))

    private fun brand(tenant: TenantKey) = ThemeId(ThemeKey.of("brand"), catalogId(tenant))

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), catalogId(tenant))

    private fun at(tenant: TenantKey, work: String) = CatalogVersionRef(tenant, letters, DraftRef.Id(work))

    /** `letters` (Brand, Invoice on it) released as 1.0.0, with a draft "Redesign 2027" from it; its id. */
    private fun withWork(name: String, from: String? = null): Pair<TenantKey, String> = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(brand(tenant), "Brand").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = letters).execute()
        mediator.releaseNext(catalogId(tenant))
        tenant to CreateCatalogDraft(tenant, letters, "Redesign 2027", from).execute().versionId
    }

    private fun releaseWork(tenant: TenantKey, work: String, version: String? = null, acknowledged: Set<String> = emptySet()) = withMediator {
        MarkCatalogReady(tenant, letters, catalogVersion = at(tenant, work)).execute()
        ReleaseCatalogVersion(tenant, letters, version, acknowledgedBehind = acknowledged, catalogVersion = at(tenant, work)).execute()
    }

    private fun drafts(tenant: TenantKey) = withMediator { ListCatalogVersions(tenant, letters, CatalogVersionStatus.DRAFT).query() }

    @Test
    fun `any draft is released, the release keeps its id, and the next version is left as it was`() {
        val (tenant, work) = withWork("release-any-draft")
        withMediator { UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, work)).execute() }
        val next = drafts(tenant).single { it.isNext }

        val released = releaseWork(tenant, work)

        assertThat(released.version).`as`("a fix against its base").isEqualTo("1.0.1")
        assertThat(released.versionId).isEqualTo(work)
        assertThat(drafts(tenant).map { it.label to it.versionId }).`as`("the draft became the release").containsExactly("Next version" to next.versionId)
        val byId = withMediator { GetCatalogVersion(tenant, letters, work).query()!! }
        assertThat(byId.version to byId.status).isEqualTo("1.0.1" to CatalogVersionStatus.RELEASED)
        assertThat(withMediator { GetTheme(brand(tenant)).query()!! }.name).`as`("the next version is untouched").isEqualTo("Brand")
    }

    @Test
    fun `a fix to an older line is an ordinary release, numbered from its base and behind nothing above it`() {
        val (tenant, _) = withWork("older-line")
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand 2").execute()
            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogVersion(tenant, letters, "2.0.0").execute()
        }
        val fix = withMediator { CreateCatalogDraft(tenant, letters, "Fixes 1.0", from = "1.0.0").execute().versionId }
        withMediator { UpdateDocumentTemplate(invoice(tenant), name = "Invoice, fixed", catalogVersion = at(tenant, fix)).execute() }

        assertThat(withMediator { GetCatalogDraft(tenant, letters, DraftRef.Id(fix), withBehind = true).query()!! }.behind).isEmpty()
        assertThat(releaseWork(tenant, fix).version).`as`("1.0.1 while 2.0.0 exists").isEqualTo("1.0.1")
        assertThat(withMediator { GetCatalog(tenant, letters).query()!! }.releasedVersion).`as`("the highest stays the catalog's").isEqualTo("2.0.0")
    }

    @Test
    fun `a draft is behind a lower release published after it started, until that is acknowledged`() {
        val (tenant, redesign) = withWork("behind-later-fix")
        withMediator { UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, redesign)).execute() }
        // A fix released from the next version after the redesign started.
        withMediator {
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice, fixed").execute()
            mediator.releaseNext(catalogId(tenant))
        }

        assertThatThrownBy { releaseWork(tenant, redesign, version = "2.0.0") }
            .isInstanceOf(CatalogVersionBehindException::class.java).hasMessageContaining("1.0.1")
        assertThat(releaseWork(tenant, redesign, version = "2.0.0", acknowledged = setOf("1.0.1")).version).isEqualTo("2.0.0")
    }

    @Test
    fun `a draft becomes the next version, the old one discarded, and neither base moves`() {
        val (tenant, work) = withWork("promote-discard")
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, work)).execute()
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice, fixed").execute()
            mediator.releaseNext(catalogId(tenant)) // 1.0.1, which the work does not contain
        }

        val promoted = withMediator { PromoteCatalogDraft(tenant, letters, DraftRef.Id(work)).execute() }

        assertThat(promoted.isNext).isTrue()
        assertThat(promoted.versionId).`as`("the same draft").isEqualTo(work)
        assertThat(promoted.base).`as`("a base is fixed").isEqualTo("1.0.0")
        assertThat(drafts(tenant).map { it.label }).containsExactly("Next version")
        assertThat(withMediator { GetTheme(brand(tenant)).query()!! }.name).`as`("what callers mean now").isEqualTo("Brand redesigned")
    }

    @Test
    fun `the old next version can be kept as another draft under a name`() {
        val (tenant, work) = withWork("promote-keep")
        withMediator { UpdateTheme(brand(tenant), name = "Brand, small fix").execute() }

        assertThatThrownBy { withMediator { PromoteCatalogDraft(tenant, letters, DraftRef.Id(work), keepPreviousAs = "redesign 2027").execute() } }
            .`as`("a name another draft has").isInstanceOf(CatalogDraftNameInUseException::class.java)
        withMediator { PromoteCatalogDraft(tenant, letters, DraftRef.Id(work), keepPreviousAs = "Small fixes").execute() }

        val drafts = drafts(tenant)
        assertThat(drafts.map { it.label to it.isNext }).containsExactly("Next version" to true, "Small fixes" to false)
        val kept = drafts.single { !it.isNext }
        assertThat(withMediator { GetTheme(brand(tenant), catalogVersion = CatalogVersionRef(tenant, letters, kept.draftRef!!)).query()!! }.name).isEqualTo("Brand, small fix")
        assertThatThrownBy { withMediator { PromoteCatalogDraft(tenant, letters, DraftRef.Next).execute() } }
            .isInstanceOf(CatalogDraftIsNextException::class.java)
    }
}
