// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Discarding a draft deletes the identity of every resource only it holds. Something outside the
 * draft that still needs one of those — the tenant's default theme, an attribute's code list, a
 * template's theme in another catalog — makes the discard refused, naming what holds it, instead of
 * surfacing as a foreign-key violation or silently clearing the reference.
 */
class DeleteCatalogVersionHeldTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun id(tenant: TenantKey, catalog: CatalogKey = letters) = CatalogId(catalog, TenantId(tenant))

    private fun at(tenant: TenantKey, version: String) = CatalogVersionRef(tenant, letters, version)

    /** `letters` with a `brand` theme, released as 1.0.0, its draft 1.0.1, and a second draft 2.0.0. */
    private fun twoDrafts(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), id(tenant)), "Brand").execute()
        mediator.releaseNext(id(tenant))
        CreateCatalogVersion(tenant, letters, "2.0.0").execute()
        CreateCatalog(tenant, shared, "Shared").execute()
        tenant
    }

    private fun versions(tenant: TenantKey) = withMediator { ListCatalogVersions(tenant, letters).query() }.map { it.version }

    @Test
    fun `a draft holding the tenant's default theme alone is not discarded`() {
        val tenant = twoDrafts("held-default-theme")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), id(tenant)), "Seasonal", catalogVersion = at(tenant, "2.0.0")).execute()
            SetTenantDefaultTheme(tenant, ThemeKey.of("seasonal"), letters).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogVersion(tenant, letters, "2.0.0").execute() } }
            .isInstanceOfSatisfying(CatalogVersionHeldException::class.java) { e ->
                assertThat(e.holders).containsExactly("the tenant's default theme (theme letters/seasonal)")
            }
            .hasMessageContaining("default theme")
        assertThat(versions(tenant)).contains("2.0.0")
    }

    @Test
    fun `a draft whose theme is the default but also held by another draft is discarded`() {
        val tenant = twoDrafts("held-default-theme-shared")
        withMediator { SetTenantDefaultTheme(tenant, ThemeKey.of("brand"), letters).execute() }

        withMediator { DeleteCatalogVersion(tenant, letters, "2.0.0").execute() }

        assertThat(versions(tenant)).doesNotContain("2.0.0")
    }

    @Test
    fun `a draft holding a code list an attribute of another catalog binds is not discarded`() {
        val tenant = twoDrafts("held-code-list")
        val colours = CodeListId(CodeListKey.of("colours"), id(tenant))
        withMediator {
            CreateCodeList(colours, "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red")), catalogVersion = at(tenant, "2.0.0")).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("colour"), id(tenant, shared)), "Colour", codeListId = colours).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogVersion(tenant, letters, "2.0.0").execute() } }
            .isInstanceOfSatisfying(CatalogVersionHeldException::class.java) { e ->
                assertThat(e.holders).containsExactly("attribute shared/colour (code list letters/colours)")
            }
        assertThat(versions(tenant)).contains("2.0.0")
    }

    @Test
    fun `a draft whose code list and the attribute binding it are both its own is discarded`() {
        val tenant = twoDrafts("held-code-list-own")
        val colours = CodeListId(CodeListKey.of("colours"), id(tenant))
        withMediator {
            CreateCodeList(colours, "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red")), catalogVersion = at(tenant, "2.0.0")).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("colour"), id(tenant)), "Colour", codeListId = colours, catalogVersion = at(tenant, "2.0.0")).execute()
        }

        withMediator { DeleteCatalogVersion(tenant, letters, "2.0.0").execute() }

        assertThat(versions(tenant)).doesNotContain("2.0.0")
    }

    @Test
    fun `a draft holding a theme a template of another catalog uses is not discarded, and the template keeps its theme`() {
        val tenant = twoDrafts("held-template-theme")
        val letter = TemplateId(TemplateKey.of("letter"), id(tenant, shared))
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), id(tenant)), "Seasonal", catalogVersion = at(tenant, "2.0.0")).execute()
            CreateDocumentTemplate(letter, "Letter").execute()
            UpdateDocumentTemplate(letter, themeId = ThemeKey.of("seasonal"), themeCatalogKey = letters).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogVersion(tenant, letters, "2.0.0").execute() } }
            .isInstanceOfSatisfying(CatalogVersionHeldException::class.java) { e ->
                assertThat(e.holders).containsExactly("template shared/letter (theme letters/seasonal)")
            }
        assertThat(withMediator { GetDocumentTemplate(letter).query()!! }.themeKey?.value).isEqualTo("seasonal")
    }
}
