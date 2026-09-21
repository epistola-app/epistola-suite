// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.identity.PreviewCatalogResourceAliasRelease
import app.epistola.suite.catalog.identity.ReleaseCatalogResourceAlias
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What happens to the addresses a resource leaves behind across several moves, and what giving one
 * up costs. Aliases name the resource's identity rather than another address, so a chain never
 * forms: every address a resource ever held points straight at wherever it lives now.
 */
class RelocationAliasLifecycleTest : RelocationTestSupport() {

    @Test
    fun `every address a resource held points straight at where it lives now`() {
        val tenant = tenantWith("Alias chain", listOf(letters, shared, archive))
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        move(tenant, header.movedTo(shared))
        move(tenant, address(CatalogResourceType.STENCIL, shared, "header").movedTo(archive, "masthead"))

        val now = address(CatalogResourceType.STENCIL, archive, "masthead")
        assertThat(resolve(tenant, header)!!.canonical).isEqualTo(now)
        assertThat(aliases(tenant)).containsExactlyInAnyOrderEntriesOf(
            mapOf("stencil:letters/header" to now.id, "stencil:shared/header" to now.id),
        )
    }

    @Test
    fun `a round trip home leaves the home address canonical and the others aliased`() {
        val tenant = tenantWith("Alias round trip", listOf(letters, shared, archive))
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        move(tenant, header.movedTo(shared))
        move(tenant, address(CatalogResourceType.STENCIL, shared, "header").movedTo(archive))
        move(tenant, address(CatalogResourceType.STENCIL, archive, "header").movedTo(letters))

        val home = resolve(tenant, header)!!
        assertThat(home.canonical).isEqualTo(header)
        assertThat(home.resolvedViaAlias).isFalse()
        assertThat(aliases(tenant)).containsExactlyInAnyOrderEntriesOf(
            mapOf("stencil:shared/header" to header.id, "stencil:archive/header" to header.id),
        )
    }

    /**
     * After a handover, the address the first resource left is occupied by the second, which wins
     * resolution over the alias still there. When the second leaves too, the address answers for
     * the one that most recently held it. References written before the handover already resolved
     * to the second resource while it lived there, so none changes meaning at that point.
     */
    @Test
    fun `an address handed over answers for its latest occupant once that one leaves too`() {
        val tenant = tenantWith("Alias handed over", listOf(letters, shared, archive))
        val original = create(tenant, MovableResource.STENCIL, letters, "header")
        val successor = create(tenant, MovableResource.STENCIL, letters, "header-next")
        val successorIdentity = resolve(tenant, successor)!!.resourceId
        move(tenant, original.movedTo(shared), successor.renamedTo("header"))
        assertThat(resolve(tenant, original)!!.resourceId).isEqualTo(successorIdentity)

        move(tenant, original.movedTo(archive))

        val resolved = resolve(tenant, original)!!
        assertThat(resolved.resourceId).isEqualTo(successorIdentity)
        assertThat(resolved.canonical).isEqualTo(address(CatalogResourceType.STENCIL, archive, "header"))
        assertThat(resolved.resolvedViaAlias).isTrue()
    }

    @Test
    fun `an address nothing was moved away from has nothing to release`() {
        val tenant = tenantWith("No alias to release")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")

        assertThat(withMediator { PreviewCatalogResourceAliasRelease(tenant, header).query() }).isNull()
        // Releasing it anyway is harmless.
        withMediator { ReleaseCatalogResourceAlias(tenant, header).execute() }
        assertThat(resolve(tenant, header)!!.canonical).isEqualTo(header)
    }

    @Test
    fun `releasing a stencil's old address reports the published references it would strand`() {
        val tenant = tenantWith("Release stencil impact")
        val header = StencilId(StencilKey.of("header"), catalogId(tenant, letters))
        publishTemplate(tenant, "invoice") {
            CreateStencil(header, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
            templateEmbedding("header", letters.value)
        }
        val old = address(CatalogResourceType.STENCIL, letters, "header")
        move(tenant, old.movedTo(shared))

        val impact = withMediator { PreviewCatalogResourceAliasRelease(tenant, old).query()!! }

        assertThat(impact.canonical).isEqualTo(address(CatalogResourceType.STENCIL, shared, "header"))
        assertThat(impact.dependentReferenceCount).isEqualTo(1)
    }

    /**
     * The release preview only ever counted stencil insertions, so releasing any other type's old
     * address reported nothing at stake while published references depended on it.
     */
    @Test
    fun `releasing a theme's old address reports the published references it would strand`() {
        val tenant = tenantWith("Release theme impact")
        publishTemplate(tenant, "invoice") {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)), "Brand").execute()
            usesTheme("brand", letters.value)
        }
        val old = address(CatalogResourceType.THEME, letters, "brand")
        move(tenant, old.movedTo(shared))

        val impact = withMediator { PreviewCatalogResourceAliasRelease(tenant, old).query()!! }

        assertThat(impact.dependentReferenceCount).isEqualTo(1)
    }

    @Test
    fun `a draft re-pointed by the move does not count as depending on the alias`() {
        val tenant = tenantWith("Release draft impact")
        val template = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)), "Brand").execute()
            CreateDocumentTemplate(template, "Invoice").execute()
            UpdateDraft(VariantId(VariantKey.INITIAL, template), usesTheme("brand", letters.value)).execute()
        }
        val old = address(CatalogResourceType.THEME, letters, "brand")
        move(tenant, old.movedTo(shared))

        assertThat(withMediator { PreviewCatalogResourceAliasRelease(tenant, old).query()!! }.dependentReferenceCount).isZero()
    }

    /** Creates template [key] in `letters` after [setup], and publishes the model [setup] returns as its first version. */
    private fun publishTemplate(tenant: TenantKey, key: String, setup: () -> TemplateDocument) {
        val template = TemplateId(TemplateKey.of(key), catalogId(tenant, letters))
        val variant = VariantId(VariantKey.INITIAL, template)
        withMediator {
            val model = setup()
            CreateDocumentTemplate(template, "Template $key").execute().withRequiredDataExample()
            UpdateDraft(variant, model).execute()
            PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute()
        }
    }
}
