// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.preReleaseLabel
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
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
import app.epistola.suite.generation.release.ReleaseTargetResolver
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
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Long-term work, stage 2 of contract ADR 0002 amendment 2: it is released as a pre-release to try
 * it, which nothing takes for the latest release, and it becomes the next version when it is ready.
 */
class PreReleaseTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resolver: ReleaseTargetResolver

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: TenantKey) = CatalogId(letters, TenantId(tenant))

    private fun brand(tenant: TenantKey) = ThemeId(ThemeKey.of("brand"), catalogId(tenant))

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), catalogId(tenant))

    private fun at(tenant: TenantKey, work: UUID) = CatalogVersionRef(tenant, letters, DraftRef.Id(work))

    /** `letters` (Brand, Invoice on it) released as 1.0.0, with long-term work "Redesign 2027"; its id. */
    private fun withWork(name: String): Pair<TenantKey, UUID> = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(brand(tenant), "Brand").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = letters).execute()
        mediator.releaseNext(catalogId(tenant))
        tenant to requireNotNull(CreateCatalogDraft(tenant, letters, "Redesign 2027").execute().draftId)
    }

    private fun releaseWork(tenant: TenantKey, work: UUID, version: String? = null) = withMediator {
        MarkCatalogReady(tenant, letters, catalogVersion = at(tenant, work)).execute()
        ReleaseCatalogVersion(tenant, letters, version, catalogVersion = at(tenant, work)).execute()
    }

    @Test
    fun `the label comes from the name, and is never numeric`() {
        assertThat(preReleaseLabel("Redesign 2027")).isEqualTo("redesign-2027")
        assertThat(preReleaseLabel("  Nieuwe huisstijl! ")).isEqualTo("nieuwe-huisstijl")
        assertThat(preReleaseLabel("2027")).isEqualTo("work-2027")
        assertThat(preReleaseLabel("***")).isEqualTo("work")
    }

    @Test
    fun `long-term work is released as a pre-release of the number its changes need, counted, and carries on`() {
        val (tenant, work) = withWork("pre-release-numbered")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("seasonal"), catalogId(tenant)), "Seasonal", catalogVersion = at(tenant, work)).execute() }

        val first = releaseWork(tenant, work)
        withMediator { UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, work)).execute() }
        val second = releaseWork(tenant, work)

        assertThat(first.version).`as`("an addition: the next minor").isEqualTo("1.1.0-redesign-2027.1")
        assertThat(second.version).isEqualTo("1.1.0-redesign-2027.2")
        assertThat(withMediator { GetCatalogDraft(tenant, letters, DraftRef.Id(work)).query() }).`as`("the work carries on").isNotNull
        assertThat(withMediator { GetCatalogDraft(tenant, letters, DraftRef.Id(work)).query()!! }.base).`as`("on its own base").isEqualTo("1.0.0")
        assertThat(withMediator { GetTheme(brand(tenant)).query()!! }.name).`as`("the next version is untouched").isEqualTo("Brand")
    }

    @Test
    fun `a pre-release is never the latest release, nor what a range or a request without an environment gets`() {
        val (tenant, work) = withWork("pre-release-not-latest")
        withMediator { UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, work)).execute() }
        val pre = releaseWork(tenant, work).version

        assertThat(pre).isEqualTo("1.0.1-redesign-2027.1")
        assertThat(withMediator { GetCatalog(tenant, letters).query()!! }.releasedVersion).isEqualTo("1.0.0")
        assertThat(withMediator { GetLatestCatalogRelease(tenant, letters).query() }.latestVersion).isEqualTo("1.0.0")
        assertThat(withMediator { resolver.selectRelease(tenant, letters, null, null) }).isEqualTo("1.0.0")
        assertThat(withMediator { resolver.selectRelease(tenant, letters, null, "*") }).isEqualTo("1.0.0")
        assertThat(withMediator { ListCatalogVersions(tenant, letters, CatalogVersionStatus.RELEASED).query() }.map { it.version })
            .`as`("listed, below nothing it is not above").containsExactly(pre, "1.0.0")
        // The next version is measured against the latest release, not the pre-release.
        assertThat(withMediator { AssessDraftNumber(tenant, letters).query()!! }.latest).isEqualTo("1.0.0")
        assertThat(withMediator { GetCatalogDraft(tenant, letters).query()!! }.behind).isEmpty()
    }

    @Test
    fun `a pre-release deployed to an environment is what that environment serves, by its number`() {
        val (tenant, work) = withWork("pre-release-deployed")
        withMediator { UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, work)).execute() }
        val pre = releaseWork(tenant, work).version
        val test = EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant))
        withMediator {
            CreateEnvironment(test, "Test").execute()
            DeployRelease(test, letters, pre).execute()
        }

        assertThat(withMediator { resolver.selectRelease(tenant, letters, EnvironmentKey.of("test"), null) }).isEqualTo(pre)
        assertThat(withMediator { resolver.selectRelease(tenant, letters, EnvironmentKey.of("test"), pre) }).isEqualTo(pre)
    }

    @Test
    fun `a number asked for must be a pre-release for long-term work, and a release for the next version`() {
        val (tenant, work) = withWork("pre-release-asked")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("seasonal"), catalogId(tenant)), "Seasonal", catalogVersion = at(tenant, work)).execute() }

        assertThatThrownBy { releaseWork(tenant, work, "2.0.0") }.isInstanceOf(CatalogReleaseVersionException::class.java).hasMessageContaining("pre-release")
        assertThatThrownBy { releaseWork(tenant, work, "1.0.1-try.1") }.`as`("an addition needs a minor").isInstanceOf(CatalogReleaseVersionException::class.java)
        assertThat(releaseWork(tenant, work, "2.0.0-beta.1").version).isEqualTo("2.0.0-beta.1")
        assertThatThrownBy {
            withMediator {
                MarkCatalogReady(tenant, letters).execute()
                ReleaseCatalogVersion(tenant, letters, "1.1.0-x.1").execute()
            }
        }
            .isInstanceOf(CatalogReleaseVersionException::class.java).hasMessageContaining("released as a release")
    }

    @Test
    fun `long-term work becomes the next version once what was published meanwhile is acknowledged, the old one discarded`() {
        val (tenant, work) = withWork("promote-discard")
        withMediator {
            UpdateTheme(brand(tenant), name = "Brand redesigned", catalogVersion = at(tenant, work)).execute()
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice, fixed").execute()
            mediator.releaseNext(catalogId(tenant)) // 1.0.1, which the work does not contain
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice, fixed again").execute()
        }

        assertThatThrownBy { withMediator { PromoteCatalogDraft(tenant, letters, work).execute() } }
            .isInstanceOf(CatalogVersionBehindException::class.java).hasMessageContaining("1.0.1")

        val promoted = withMediator { PromoteCatalogDraft(tenant, letters, work, acknowledgedBehind = setOf("1.0.1")).execute() }

        assertThat(promoted.isNext).isTrue()
        assertThat(promoted.base).`as`("continues on the latest release").isEqualTo("1.0.1")
        assertThat(promoted.behind).isEmpty()
        assertThat(withMediator { ListCatalogVersions(tenant, letters, CatalogVersionStatus.DRAFT).query() }.map { it.label }).containsExactly("Next version")
        assertThat(withMediator { GetTheme(brand(tenant)).query()!! }.name).`as`("what callers mean now").isEqualTo("Brand redesigned")
        // Released as the next version from now on.
        assertThat(withMediator { mediator.releaseNext(catalogId(tenant)) }).isEqualTo("1.0.2")
    }

    @Test
    fun `the old next version can be kept as long-term work under a name`() {
        val (tenant, work) = withWork("promote-keep")
        withMediator { UpdateTheme(brand(tenant), name = "Brand, small fix").execute() }

        assertThatThrownBy { withMediator { PromoteCatalogDraft(tenant, letters, work, keepPreviousAs = "redesign 2027").execute() } }
            .`as`("a name another draft has").isInstanceOf(CatalogDraftNameInUseException::class.java)
        withMediator { PromoteCatalogDraft(tenant, letters, work, keepPreviousAs = "Small fixes").execute() }

        val drafts = withMediator { ListCatalogVersions(tenant, letters, CatalogVersionStatus.DRAFT).query() }
        assertThat(drafts.map { it.label to it.isNext }).containsExactly("Next version" to true, "Small fixes" to false)
        val kept = drafts.single { !it.isNext }
        assertThat(withMediator { GetTheme(brand(tenant), catalogVersion = CatalogVersionRef(tenant, letters, kept.draftRef!!)).query()!! }.name).isEqualTo("Brand, small fix")
        assertThatThrownBy { withMediator { PromoteCatalogDraft(tenant, letters, drafts.single { it.isNext }.draftId!!).execute() } }
            .isInstanceOf(CatalogDraftIsNextException::class.java)
    }
}
