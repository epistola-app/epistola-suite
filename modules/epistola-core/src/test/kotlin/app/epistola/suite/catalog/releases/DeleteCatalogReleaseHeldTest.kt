// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Discarding a draft deletes the identity of every resource only it holds. Something outside the
 * draft that still needs one of those — the tenant's default theme, an attribute's code list, a
 * template's theme in another catalog — makes the discard refused, naming what holds it, instead of
 * surfacing as a foreign-key violation or silently clearing the reference.
 */
class DeleteCatalogReleaseHeldTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun id(tenant: TenantKey, catalog: CatalogKey = letters) = CatalogId(catalog, TenantId(tenant))

    /**
     * `letters` with a `brand` theme, released as 1.0.0, its next version, and parallel work "Redesign".
     * What is created below goes into the next version, the one everything outside the catalog sees:
     * discarding it would take a resource that is in no release away from what uses it.
     */
    private fun twoDrafts(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), id(tenant)), "Brand").execute()
        mediator.releaseNext(id(tenant))
        CreateCatalogDraft(tenant, letters, "Redesign").execute()
        CreateCatalog(tenant, shared, "Shared").execute()
        tenant
    }

    /** Which resources the next version holds, by type and key. */
    private fun inNext(tenant: TenantKey, type: String) = withMediator { GetCatalogResourceChanges(tenant, letters).query() }.resources
        .filter { it.type == type }.map { it.slug }

    @Test
    fun `a next version holding the tenant's default theme alone is not discarded`() {
        val tenant = twoDrafts("held-default-theme")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), id(tenant)), "Seasonal").execute()
            SetTenantDefaultTheme(tenant, ThemeKey.of("seasonal"), letters).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogDraft(tenant, letters, DraftRef.Next).execute() } }
            .isInstanceOfSatisfying(CatalogReleaseHeldException::class.java) { e ->
                assertThat(e.holders).containsExactly("the tenant's default theme (theme letters/seasonal)")
            }
            .hasMessageContaining("default theme")
        assertThat(inNext(tenant, "theme")).`as`("nothing was discarded").contains("seasonal")
    }

    @Test
    fun `a next version whose theme is the default but also held by a release is discarded`() {
        val tenant = twoDrafts("held-default-theme-shared")
        withMediator {
            UpdateTheme(ThemeId(ThemeKey.of("brand"), id(tenant)), name = "Brand, edited").execute()
            SetTenantDefaultTheme(tenant, ThemeKey.of("brand"), letters).execute()
        }

        withMediator { DeleteCatalogDraft(tenant, letters, DraftRef.Next).execute() }

        assertThat(withMediator { GetTheme(ThemeId(ThemeKey.of("brand"), id(tenant))).query()!! }.name).`as`("back to the release").isEqualTo("Brand")
    }

    @Test
    fun `a next version holding a code list an attribute of another catalog binds is not discarded`() {
        val tenant = twoDrafts("held-code-list")
        val colours = CodeListId(CodeListKey.of("colours"), id(tenant))
        withMediator {
            CreateCodeList(colours, "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red"))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("colour"), id(tenant, shared)), "Colour", codeListId = colours).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogDraft(tenant, letters, DraftRef.Next).execute() } }
            .isInstanceOfSatisfying(CatalogReleaseHeldException::class.java) { e ->
                assertThat(e.holders).containsExactly("attribute shared/colour (code list letters/colours)")
            }
        assertThat(inNext(tenant, "codeList")).`as`("nothing was discarded").contains("colours")
    }

    @Test
    fun `a next version whose code list and the attribute binding it are both its own is discarded`() {
        val tenant = twoDrafts("held-code-list-own")
        val colours = CodeListId(CodeListKey.of("colours"), id(tenant))
        withMediator {
            CreateCodeList(colours, "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red"))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("colour"), id(tenant)), "Colour", codeListId = colours).execute()
        }

        withMediator { DeleteCatalogDraft(tenant, letters, DraftRef.Next).execute() }

        assertThat(inNext(tenant, "codeList")).`as`("the next version started again on the release").doesNotContain("colours")
    }

    @Test
    fun `a next version holding a theme a template of another catalog uses is not discarded, and the template keeps its theme`() {
        val tenant = twoDrafts("held-template-theme")
        val letter = TemplateId(TemplateKey.of("letter"), id(tenant, shared))
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), id(tenant)), "Seasonal").execute()
            CreateDocumentTemplate(letter, "Letter").execute()
            UpdateDocumentTemplate(letter, themeId = ThemeKey.of("seasonal"), themeCatalogKey = letters).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogDraft(tenant, letters, DraftRef.Next).execute() } }
            .isInstanceOfSatisfying(CatalogReleaseHeldException::class.java) { e ->
                assertThat(e.holders).containsExactly("template shared/letter (theme letters/seasonal)")
            }
        assertThat(withMediator { GetDocumentTemplate(letter).query()!! }.themeKey?.value).isEqualTo("seasonal")
    }
}
