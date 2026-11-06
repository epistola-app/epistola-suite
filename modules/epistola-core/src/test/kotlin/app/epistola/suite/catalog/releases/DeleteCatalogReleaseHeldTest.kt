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
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
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
import app.epistola.suite.testing.testCatalog
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

    private fun id(tenant: TenantKey, catalog: CatalogKey = letters) = testCatalog(TenantId(tenant), catalog)

    /**
     * `letters` with a `brand` theme, released as 1.0.0, its next version, and parallel work "Redesign".
     * What is created below goes into the next version, the one everything outside the catalog sees:
     * discarding it would take a resource that is in no release away from what uses it.
     */
    private fun twoDrafts(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), id(tenant)), "Brand", catalogVersion = null).execute()
        mediator.releaseNext(id(tenant))
        CreateCatalogDraft(testCatalog(tenant, letters), "Redesign").execute()
        CreateCatalog(tenant, shared, "Shared").execute()
        tenant
    }

    /** Which resources the next version holds, by type and key. */
    private fun inNext(tenant: TenantKey, type: String) = withMediator { GetCatalogResourceChanges(testCatalog(tenant, letters), catalogVersion = null).query() }.resources
        .filter { it.type == type }.map { it.slug }

    @Test
    fun `a next version holding the tenant's default theme alone is not discarded`() {
        val tenant = twoDrafts("held-default-theme")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), id(tenant)), "Seasonal", catalogVersion = null).execute()
            SetTenantDefaultTheme(tenant, testCatalog(tenant, letters), ThemeKey.of("seasonal")).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogDraft(testCatalog(tenant, letters), DraftRef.Next).execute() } }
            .isInstanceOfSatisfying(CatalogReleaseHeldException::class.java) { e ->
                assertThat(e.holders).containsExactly("the tenant's default theme (theme letters/seasonal)")
                assertThat(e.heldResources).containsExactly("theme/seasonal")
                assertThat(e.dependents).isEmpty()
            }
            .hasMessageContaining("default theme")
        assertThat(inNext(tenant, "theme")).`as`("nothing was discarded").contains("seasonal")
    }

    @Test
    fun `a draft another catalog's draft builds on is not discarded, naming that draft in full`() {
        val tenant = twoDrafts("named-by-draft")
        val (redesign, sharedNext) = withMediator {
            val redesign = requireNotNull(GetCatalogDraft(id(tenant), DraftRef.Id(draftKey(tenant, "Redesign"))).query())
            val target = requireNotNull(GetCatalog(id(tenant)).query())
            UpdateCatalogDependencies(
                id(tenant, shared),
                listOf(DeclaredCatalogDependency(target.registry, target.namespace, letters.value, versionId = redesign.versionId)),
                catalogVersion = null,
            ).execute()
            redesign to requireNotNull(GetCatalogDraft(id(tenant, shared)).query())
        }
        val sharedCatalog = withMediator { requireNotNull(GetCatalog(id(tenant, shared)).query()) }

        assertThatThrownBy { withMediator { DeleteCatalogDraft(id(tenant), DraftRef.Id(redesign.versionId)).execute() } }
            .isInstanceOfSatisfying(CatalogDraftInUseException::class.java) { e ->
                assertThat(e.dependents).containsExactly(
                    CatalogDraftReference(sharedCatalog.registry, sharedCatalog.namespace, shared.value, sharedNext.versionId, CatalogDraft.NEXT_LABEL),
                )
            }
            .hasMessageContaining("shared (${CatalogDraft.NEXT_LABEL})")
    }

    @Test
    fun `a held draft also names the drafts of other catalogs building on it`() {
        val tenant = twoDrafts("held-and-named")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), id(tenant)), "Seasonal", catalogVersion = null).execute()
            SetTenantDefaultTheme(tenant, testCatalog(tenant, letters), ThemeKey.of("seasonal")).execute()
            val target = requireNotNull(GetCatalog(id(tenant)).query())
            val next = requireNotNull(GetCatalogDraft(id(tenant)).query())
            UpdateCatalogDependencies(
                id(tenant, shared),
                listOf(DeclaredCatalogDependency(target.registry, target.namespace, letters.value, versionId = next.versionId)),
                catalogVersion = null,
            ).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogDraft(id(tenant), DraftRef.Next).execute() } }
            .isInstanceOfSatisfying(CatalogReleaseHeldException::class.java) { e ->
                assertThat(e.heldResources).containsExactly("theme/seasonal")
                assertThat(e.dependents.map { it.catalogKey }).containsExactly(shared.value)
            }
    }

    /** The id of [tenant]'s `letters` draft named [name]. */
    private fun draftKey(tenant: TenantKey, name: String): String = withMediator {
        ListCatalogReleases(id(tenant)).query().single { it.status == CatalogReleaseStatus.DRAFT && it.name == name }.versionId
    }

    @Test
    fun `a next version whose theme is the default but also held by a release is discarded`() {
        val tenant = twoDrafts("held-default-theme-shared")
        withMediator {
            UpdateTheme(ThemeId(ThemeKey.of("brand"), id(tenant)), name = "Brand, edited", catalogVersion = null).execute()
            SetTenantDefaultTheme(tenant, testCatalog(tenant, letters), ThemeKey.of("brand")).execute()
        }

        withMediator { DeleteCatalogDraft(testCatalog(tenant, letters), DraftRef.Next).execute() }

        assertThat(withMediator { GetTheme(ThemeId(ThemeKey.of("brand"), id(tenant)), catalogVersion = null).query()!! }.name).`as`("back to the release").isEqualTo("Brand")
    }

    @Test
    fun `a next version holding a code list an attribute of another catalog binds is not discarded`() {
        val tenant = twoDrafts("held-code-list")
        val colours = CodeListId(CodeListKey.of("colours"), id(tenant))
        withMediator {
            CreateCodeList(colours, "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red")), catalogVersion = null).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("colour"), id(tenant, shared)), "Colour", codeListId = colours, catalogVersion = null).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogDraft(testCatalog(tenant, letters), DraftRef.Next).execute() } }
            .isInstanceOfSatisfying(CatalogReleaseHeldException::class.java) { e ->
                assertThat(e.holders).containsExactly("attribute shared/colour (code list letters/colours)")
                assertThat(e.heldResources).containsExactly("code-list/colours")
            }
        assertThat(inNext(tenant, "codeList")).`as`("nothing was discarded").contains("colours")
    }

    @Test
    fun `a next version whose code list and the attribute binding it are both its own is discarded`() {
        val tenant = twoDrafts("held-code-list-own")
        val colours = CodeListId(CodeListKey.of("colours"), id(tenant))
        withMediator {
            CreateCodeList(colours, "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red")), catalogVersion = null).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("colour"), id(tenant)), "Colour", codeListId = colours, catalogVersion = null).execute()
        }

        withMediator { DeleteCatalogDraft(testCatalog(tenant, letters), DraftRef.Next).execute() }

        assertThat(inNext(tenant, "codeList")).`as`("the next version started again on the release").doesNotContain("colours")
    }

    @Test
    fun `a next version holding a theme a template of another catalog uses is not discarded, and the template keeps its theme`() {
        val tenant = twoDrafts("held-template-theme")
        val letter = TemplateId(TemplateKey.of("letter"), id(tenant, shared))
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), id(tenant)), "Seasonal", catalogVersion = null).execute()
            CreateDocumentTemplate(letter, "Letter", catalogVersion = null).execute()
            UpdateDocumentTemplate(letter, themeId = ThemeKey.of("seasonal"), themeCatalog = testCatalog(letter.tenantKey, letters), catalogVersion = null).execute()
        }

        assertThatThrownBy { withMediator { DeleteCatalogDraft(testCatalog(tenant, letters), DraftRef.Next).execute() } }
            .isInstanceOfSatisfying(CatalogReleaseHeldException::class.java) { e ->
                assertThat(e.holders).containsExactly("template shared/letter (theme letters/seasonal)")
            }
        assertThat(withMediator { GetDocumentTemplate(letter, catalogVersion = null).query()!! }.themeKey?.value).isEqualTo("seasonal")
    }
}
