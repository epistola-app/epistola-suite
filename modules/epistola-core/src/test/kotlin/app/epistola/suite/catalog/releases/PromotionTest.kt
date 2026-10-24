// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.ContinueEverydayWork
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.catalog.releases.GetCatalogRelease
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
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Any draft is released on its own (contract ADR 0002, "Releasing any draft"), and the release keeps
 * the draft's id. Which draft is the next version is this suite's convention: parallel work takes it
 * over only by being released for everyday work to continue from, and nothing is merged.
 */
class PromotionTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: TenantKey) = testCatalog(TenantId(tenant), letters)

    private fun brand(tenant: TenantKey) = ThemeId(ThemeKey.of("brand"), catalogId(tenant))

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), catalogId(tenant))

    private fun at(tenant: TenantKey, work: String) = CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Id(work))

    /** `letters` (Brand, Invoice on it) released as 1.0.0, with a draft "Redesign 2027" from it; its id. */
    private fun withWork(name: String, from: String? = null): Pair<TenantKey, String> = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(brand(tenant), "Brand").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(invoice(tenant).tenantKey, letters)).execute()
        mediator.releaseNext(catalogId(tenant))
        tenant to CreateCatalogDraft(testCatalog(tenant, letters), "Redesign 2027", from).execute().versionId
    }

    private fun releaseWork(tenant: TenantKey, work: String, version: String? = null, acknowledged: Set<String> = emptySet()) = withMediator {
        MarkCatalogReady(testCatalog(tenant, letters), catalogVersion = at(tenant, work)).execute()
        ReleaseCatalogDraft(testCatalog(tenant, letters), version, acknowledgedBehind = acknowledged, catalogVersion = at(tenant, work)).execute()
    }

    private fun drafts(tenant: TenantKey) = withMediator { ListCatalogReleases(testCatalog(tenant, letters), CatalogReleaseStatus.DRAFT).query() }

    @Test
    fun `any draft is released, the release keeps its id, and the next version is left as it was`() {
        val (tenant, work) = withWork("release-any-draft")
        withMediator { UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, work)).execute() }
        val next = drafts(tenant).single { it.isNext }

        val released = releaseWork(tenant, work)

        assertThat(released.version).`as`("a fix against its base").isEqualTo("1.0.1")
        assertThat(released.versionId).isEqualTo(work)
        assertThat(drafts(tenant).map { it.label to it.versionId }).`as`("the draft became the release").containsExactly("Next release" to next.versionId)
        val byId = withMediator { GetCatalogRelease(testCatalog(tenant, letters), work).query()!! }
        assertThat(byId.version to byId.status).isEqualTo("1.0.1" to CatalogReleaseStatus.RELEASED)
        assertThat(withMediator { GetTheme(brand(tenant)).query()!! }.name).`as`("the next version is untouched").isEqualTo("Brand")
    }

    @Test
    fun `a fix to an older line is an ordinary release, numbered from its base and behind nothing above it`() {
        val (tenant, _) = withWork("older-line")
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand 2").execute()
            MarkCatalogReady(testCatalog(tenant, letters)).execute()
            ReleaseCatalogDraft(testCatalog(tenant, letters), "2.0.0").execute()
        }
        val fix = withMediator { CreateCatalogDraft(testCatalog(tenant, letters), "Fixes 1.0", from = "1.0.0").execute().versionId }
        withMediator { UpdateDocumentTemplate(invoice(tenant), name = "Invoice, fixed", catalogVersion = at(tenant, fix)).execute() }

        assertThat(withMediator { GetCatalogDraft(testCatalog(tenant, letters), DraftRef.Id(fix), withBehind = true).query()!! }.behind).isEmpty()
        assertThat(releaseWork(tenant, fix).version).`as`("1.0.1 while 2.0.0 exists").isEqualTo("1.0.1")
        assertThat(withMediator { GetCatalog(testCatalog(tenant, letters)).query()!! }.releasedVersion).`as`("the highest stays the catalog's").isEqualTo("2.0.0")
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
            .isInstanceOf(CatalogReleaseBehindException::class.java).hasMessageContaining("1.0.1")
        assertThat(releaseWork(tenant, redesign, version = "2.0.0", acknowledged = setOf("1.0.1")).version).isEqualTo("2.0.0")
    }

    private fun releaseForEverydayWork(tenant: TenantKey, work: String, keepPreviousAs: String? = null) = withMediator {
        MarkCatalogReady(testCatalog(tenant, letters), catalogVersion = at(tenant, work)).execute()
        ReleaseCatalogDraft(testCatalog(tenant, letters), catalogVersion = at(tenant, work), continueEverydayWork = ContinueEverydayWork(keepPreviousAs)).execute()
    }

    @Test
    fun `parallel work released for everyday work to continue from becomes the next version on its release, and the old one's changes are not merged`() {
        val (tenant, work) = withWork("continue-discard")
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, work)).execute()
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice, fixed").execute() // in the next version, never published
        }

        val released = releaseForEverydayWork(tenant, work)

        assertThat(released.versionId).`as`("the release keeps the draft's id").isEqualTo(work)
        val next = drafts(tenant).single()
        assertThat(next.isNext).isTrue()
        assertThat(next.versionId).`as`("it continues under a new id").isNotEqualTo(work)
        assertThat(next.base).`as`("measured against the release").isEqualTo(released.version)
        assertThat(withMediator { GetTheme(brand(tenant)).query()!! }.name).isEqualTo("Brand redesigned")
        assertThat(withMediator { GetDocumentTemplate(invoice(tenant)).query()!! }.name)
            .`as`("the old next version was discarded, not merged").isEqualTo("Invoice")
    }

    @Test
    fun `the old next version can be kept as parallel work under a name, and a name in use refuses the release`() {
        val (tenant, work) = withWork("continue-keep")
        withMediator { UpdateTheme(brand(tenant), name = "Brand, small fix").execute() }

        assertThatThrownBy { releaseForEverydayWork(tenant, work, keepPreviousAs = "redesign 2027") }
            .`as`("a name another draft has").isInstanceOf(CatalogDraftNameInUseException::class.java)
        assertThat(drafts(tenant).map { it.versionId }).`as`("nothing was released").contains(work)

        releaseForEverydayWork(tenant, work, keepPreviousAs = "Small fixes")

        val drafts = drafts(tenant)
        assertThat(drafts.map { it.label to it.isNext }).containsExactlyInAnyOrder("Next release" to true, "Small fixes" to false)
        val kept = drafts.single { !it.isNext }
        assertThat(withMediator { GetTheme(brand(tenant), catalogVersion = CatalogReleaseRef(testCatalog(tenant, letters), kept.draftRef!!)).query()!! }.name).isEqualTo("Brand, small fix")
    }
}
