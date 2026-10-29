// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.merge.MergeMovedTo
import app.epistola.suite.catalog.merge.MergeOutcome
import app.epistola.suite.catalog.merge.MergeReleaseIntoDraft
import app.epistola.suite.catalog.merge.MergeResolution
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Merging a release made from a draft a refactoring left alone (ADR 0042): the merge deduces the move
 * from identities (the merge base says which identity was at the old address, the registry where it
 * is now), so what the release changed that names the old address follows the move.
 */
class MergeAfterRelocationTest : RelocationTestSupport() {

    @Test
    fun `a fix released from a draft left alone merges into the draft that moved what it names`() {
        val tenant = movedWhileHotfixChangedIt("merge-after-move")

        val preview = withMediator { PreviewDraftMerge(catalogId(tenant, letters), "1.0.1").query() }
        val outcomes = preview.resources.associateBy { it.contentKey }
        // The fix to the template names the theme where it was: it follows the move, not a conflict.
        assertThat(outcomes.getValue("template/invoice").outcome).isEqualTo(MergeOutcome.THEIRS)
        // The fix to the moved theme is asked about, saying where the theme went.
        val house = outcomes.getValue("theme/house")
        assertThat(house.outcome).isEqualTo(MergeOutcome.CONFLICT)
        assertThat(house.movedTo).isEqualTo(MergeMovedTo(catalogKey = "shared", key = "house"))

        withMediator { MergeReleaseIntoDraft(catalogId(tenant, letters), "1.0.1", mapOf("theme/house" to MergeResolution.Ours), expectedToken = preview.token).execute() }

        val invoice = withMediator { GetDocumentTemplate(invoice(tenant)).query()!! }
        assertThat(invoice.name).isEqualTo("Invoice fixed")
        assertThat(invoice.themeKey?.value to invoice.themeCatalogKey?.value).isEqualTo("house" to "shared")
    }

    /**
     * `letters` with a theme `house` and a template `invoice` using it, released as 1.0.0. A hotfix
     * started from 1.0.0 fixes both and is released as 1.0.1, after the next version moved the theme
     * to `shared`.
     */
    private fun movedWhileHotfixChangedIt(name: String): TenantKey {
        val tenant = tenantWith(name)
        val hotfix = withMediator {
            CreateTheme(ThemeId(ThemeKey.of("house"), catalogId(tenant, letters)), "House").execute()
            CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("house"), themeCatalog = catalogId(tenant, letters)).execute()
            MarkCatalogReady(catalogId(tenant, letters)).execute()
            ReleaseCatalogDraft(catalog = catalogId(tenant, letters), version = "1.0.0").execute()
            CatalogReleaseRef(catalogId(tenant, letters), DraftRef.Id(CreateCatalogDraft(catalogId(tenant, letters), "Hotfix", "1.0.0").execute().versionId))
        }

        move(tenant, address(tenant, CatalogResourceType.THEME, letters, "house").movedTo(shared))

        withMediator {
            UpdateDocumentTemplate(invoice(tenant), name = "Invoice fixed", catalogVersion = hotfix).execute()
            UpdateTheme(ThemeId(ThemeKey.of("house"), catalogId(tenant, letters)), name = "House fixed", catalogVersion = hotfix).execute()
            MarkCatalogReady(catalogId(tenant, letters), catalogVersion = hotfix).execute()
            ReleaseCatalogDraft(catalog = catalogId(tenant, letters), version = "1.0.1", catalogVersion = hotfix).execute()
        }
        return tenant
    }

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
}
