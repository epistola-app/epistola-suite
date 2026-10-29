// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.ResourceIdentity
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.MarkTemplateReady
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A refactoring changes one draft per catalog and leaves the others alone (ADR 0042): the moved
 * resource keeps its identity, so what follows it (generation history, quality findings) follows the
 * move, and a draft left behind keeps its copy at the old address under an identity of its own.
 */
class RelocationWithSeveralDraftsTest : RelocationTestSupport() {

    @Test
    fun `a move leaves another draft's copy behind under an identity of its own`() {
        val tenant = lettersReleasedWithHotfix("several-drafts-theme")
        val house = address(tenant, CatalogResourceType.THEME, letters, "house")
        val before = requireNotNull(identityAt(tenant, house))

        move(tenant, house.movedTo(shared))

        assertThat(identityAt(tenant, address(tenant, CatalogResourceType.THEME, shared, "house"))).isEqualTo(before)
        val leftBehind = identityAt(tenant, house)
        assertThat(leftBehind).isNotNull.isNotEqualTo(before)
        assertThat(releasedIdentity(tenant, house, "1.0.0")).describedAs("a release keeps the identity it was cut with").isEqualTo(before)

        // The hotfix is untouched: its theme and the template using it stay at the old address.
        assertThat(withMediator { GetTheme(ThemeId(ThemeKey.of("house"), catalogId(tenant, letters)), hotfix(tenant)).query() }).isNotNull
        val hotfixInvoice = withMediator { GetDocumentTemplate(invoice(tenant), hotfix(tenant)).query()!! }
        assertThat(hotfixInvoice.themeKey?.value to hotfixInvoice.themeCatalogKey?.value).isEqualTo("house" to "letters")
        // The draft the move changed follows it.
        val nextInvoice = withMediator { GetDocumentTemplate(invoice(tenant)).query()!! }
        assertThat(nextInvoice.themeKey?.value to nextInvoice.themeCatalogKey?.value).isEqualTo("house" to "shared")
    }

    @Test
    fun `a template moved while another draft holds it leaves that draft's copy whole and ready`() {
        val tenant = lettersReleasedWithHotfix("several-drafts-template")
        withMediator { MarkTemplateReady(invoice(tenant), catalogVersion = hotfix(tenant)).execute() }
        val invoice = address(tenant, CatalogResourceType.TEMPLATE, letters, "invoice")
        val before = requireNotNull(identityAt(tenant, invoice))

        move(tenant, invoice.movedTo(shared))

        assertThat(identityAt(tenant, address(tenant, CatalogResourceType.TEMPLATE, shared, "invoice"))).isEqualTo(before)
        assertThat(identityAt(tenant, invoice)).isNotNull.isNotEqualTo(before)
        val variants = withMediator { GetVariantSummaries(invoice(tenant), catalogVersion = hotfix(tenant)).query() }
        assertThat(variants).isNotEmpty
        assertThat(variants.map { it.ready }).describedAs("the hotfix's ready marks follow its copy").containsOnly(true)
    }

    @Test
    fun `what uses a resource moved into a released catalog depends on that catalog's draft`() {
        val tenant = lettersReleasedWithHotfix("several-drafts-dependency")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("plain"), catalogId(tenant, shared)), "Plain").execute()
            mediator.releaseNext(catalogId(tenant, shared))
        }

        move(tenant, address(tenant, CatalogResourceType.THEME, letters, "house").movedTo(shared))

        // No release of shared holds the theme yet: only its draft does.
        val onShared = withMediator { ListCatalogDependencies(catalogId(tenant, letters)).query() }.single { it.catalogKey == "shared" }
        assertThat(onShared.releaseVersion).isNull()
        assertThat(onShared.draftId).isNotNull
        assertThat(withMediator { ListCatalogDependencies(catalogId(tenant, letters), hotfix(tenant)).query() }).describedAs("the hotfix is left alone").isEmpty()
    }

    @Test
    fun `a move from the draft you work in changes that draft and leaves the next version alone`() {
        val tenant = lettersReleasedWithHotfix("several-drafts-from-hotfix")
        val house = address(tenant, CatalogResourceType.THEME, letters, "house")
        val before = requireNotNull(identityAt(tenant, house))
        val relocation = house.movedTo(shared)

        val plan = withMediator { PreviewCatalogResourceMove(tenant, listOf(relocation), hotfix(tenant)).query() }
        assertThat(plan.blockers).isEmpty()
        assertThat(plan.warnings.filter { it.code == "left-behind-draft" }.map { it.message }).singleElement().asString().startsWith("The next release of ")
        withMediator { MoveCatalogResources(tenant, listOf(relocation), plan.planFingerprint, hotfix(tenant)).execute() }

        assertThat(identityAt(tenant, address(tenant, CatalogResourceType.THEME, shared, "house"))).isEqualTo(before)
        assertThat(withMediator { GetTheme(ThemeId(ThemeKey.of("house"), catalogId(tenant, letters)), hotfix(tenant)).query() }).isNull()
        val hotfixInvoice = withMediator { GetDocumentTemplate(invoice(tenant), hotfix(tenant)).query()!! }
        assertThat(hotfixInvoice.themeKey?.value to hotfixInvoice.themeCatalogKey?.value).isEqualTo("house" to "shared")
        // The next version keeps its copy and its template, at the old address.
        assertThat(withMediator { GetTheme(ThemeId(ThemeKey.of("house"), catalogId(tenant, letters))).query() }).isNotNull
        val nextInvoice = withMediator { GetDocumentTemplate(invoice(tenant)).query()!! }
        assertThat(nextInvoice.themeKey?.value to nextInvoice.themeCatalogKey?.value).isEqualTo("house" to "letters")
    }

    @Test
    fun `the draft to work in must be of a catalog a resource moves out of`() {
        val tenant = lettersReleasedWithHotfix("several-drafts-other-catalog")
        val relocation = address(tenant, CatalogResourceType.THEME, letters, "house").movedTo(shared)
        assertThatThrownBy { PreviewCatalogResourceMove(tenant, listOf(relocation), CatalogReleaseRef(catalogId(tenant, shared), DraftRef.Next)) }
            .isInstanceOf(ValidationException::class.java)
    }

    /**
     * `letters` with a theme `house` and a template `invoice` using it, released as 1.0.0, and a draft
     * called Hotfix started from that release beside the next version.
     */
    private fun lettersReleasedWithHotfix(name: String): TenantKey {
        val tenant = tenantWith(name)
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("house"), catalogId(tenant, letters)), "House").execute()
            CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("house"), themeCatalog = catalogId(tenant, letters)).execute()
            mediator.releaseNext(catalogId(tenant, letters))
            hotfixes[tenant] = DraftRef.Id(CreateCatalogDraft(catalogId(tenant, letters), "Hotfix", "1.0.0").execute().versionId)
        }
        return tenant
    }

    private val hotfixes = mutableMapOf<TenantKey, DraftRef>()

    private fun hotfix(tenant: TenantKey) = CatalogReleaseRef(catalogId(tenant, letters), hotfixes.getValue(tenant))

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))

    private fun address(tenant: TenantKey, type: CatalogResourceType, catalog: CatalogKey, key: String) = RelocationTestSupport.address(tenant, type, catalog, key)

    /** The identity release [version] of the address's catalog holds at that address. */
    private fun releasedIdentity(tenant: TenantKey, address: app.epistola.suite.catalog.graph.ResourceAddress, version: String): ResourceIdentity? = jdbi.withHandle<ResourceIdentity?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.resource_id FROM release_wire_resources r
            JOIN catalog_versions v ON v.tenant_key = r.tenant_key AND v.version_id = r.version_id
            WHERE r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND v.state = 'released' AND v.version = :version
              AND r.resource_type = :type AND r.resource_key = :key
            """,
        )
            .bind("t", tenant)
            .bindCatalog("c", app.epistola.suite.testing.testCatalog(tenant, address.catalogKey))
            .bind("version", version)
            .bind("type", address.type.wireName)
            .bind("key", address.key)
            .map { rs, _ -> ResourceIdentity.of(rs.getString("resource_id")) }
            .findOne()
            .orElse(null)
    }
}
