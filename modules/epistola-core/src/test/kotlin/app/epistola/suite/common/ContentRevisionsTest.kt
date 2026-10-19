// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.stencilDocument
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.SetDefaultVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Optimistic locking of editable content: a write that names the revision it read is refused when
 * the content changed since, and leaves it as it was; a write without one writes as it always did.
 * Only a change raises the revision.
 */
class ContentRevisionsTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun catalog(name: String): CatalogId {
        val tenant = createTenant(name).id
        withMediator { CreateCatalog(tenant, letters, "Letters").execute() }
        return CatalogId(letters, TenantId(tenant))
    }

    private fun document(text: String): TemplateDocument = stencilDocument {
        text("t", text)
        build("t")
    }

    private fun template(catalog: CatalogId): VariantId {
        val templateId = TemplateId(TemplateKey.of("invoice"), catalog)
        withMediator { CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample() }
        return VariantId(VariantKey.INITIAL, templateId)
    }

    @Test
    fun `a variant saved against a stale revision is refused and keeps the other save`() {
        val variant = template(catalog("rev-variant"))
        withMediator {
            val opened = GetVariant(variant).query()!!.revision

            val first = UpdateVariantContent(variant, document("mine"), expectedRevision = opened).execute()
            assertThat(first.revision).isEqualTo(opened + 1)

            assertThatThrownBy { UpdateVariantContent(variant, document("theirs"), expectedRevision = opened).execute() }
                .isInstanceOfSatisfying(ResourceModifiedException::class.java) {
                    assertThat(it.expected).isEqualTo(opened)
                    assertThat(it.current).isEqualTo(opened + 1)
                }
            assertThat(GetVariantContent(variant).query()!!.toString()).`as`("the first save stands").contains("mine").doesNotContain("theirs")

            assertThatThrownBy { UpdateVariant(variant, "Renamed", emptyMap(), expectedRevision = opened).execute() }
                .isInstanceOf(ResourceModifiedException::class.java)
            assertThat(UpdateVariant(variant, "Renamed", emptyMap(), expectedRevision = first.revision).execute()!!.revision).isEqualTo(first.revision + 1)

            // Without a revision, last write wins, as before.
            assertThat(UpdateVariantContent(variant, document("anyway")).execute().revision).isEqualTo(first.revision + 2)
        }
    }

    @Test
    fun `saving unchanged content or making another variant the default keeps the revision`() {
        val variant = template(catalog("rev-unchanged"))
        withMediator {
            val saved = UpdateVariantContent(variant, document("same")).execute()
            assertThat(UpdateVariantContent(variant, document("same"), expectedRevision = saved.revision).execute().revision)
                .`as`("nothing changed").isEqualTo(saved.revision)

            val other = VariantId(VariantKey.of("other"), variant.templateId)
            CreateVariant(other, "Other", null).execute()
            SetDefaultVariant(other).execute()
            assertThat(GetVariant(variant).query()!!.revision).`as`("losing the default is not an edit of its content").isEqualTo(saved.revision)
        }
    }

    @Test
    fun `a template's details and data contract share its revision`() {
        val templateId = template(catalog("rev-template")).templateId
        withMediator {
            val opened = GetDocumentTemplate(templateId).query()!!.revision
            assertThat(GetDataContract(templateId).query()!!.revision).isEqualTo(opened)

            val renamed = UpdateDocumentTemplate(templateId, name = "Renamed", expectedRevision = opened).execute()!!
            assertThat(renamed.revision).isEqualTo(opened + 1)
            assertThatThrownBy { UpdateDataContract(templateId, dataExamples = GetDataContract(templateId).query()!!.dataExamples, expectedRevision = opened).execute() }
                .isInstanceOf(ResourceModifiedException::class.java)
            assertThatThrownBy { UpdateDocumentTemplate(templateId, name = "Again", expectedRevision = opened).execute() }
                .isInstanceOf(ResourceModifiedException::class.java)
            assertThat(GetDocumentTemplate(templateId).query()!!.name).isEqualTo("Renamed")
        }
    }

    @Test
    fun `themes, stencils, code lists and attributes refuse a stale revision`() {
        val catalog = catalog("rev-others")
        withMediator {
            val theme = ThemeId(ThemeKey.of("brand"), catalog)
            CreateTheme(theme, "Brand").execute()
            val themeAt = GetTheme(theme).query()!!.revision
            UpdateTheme(theme, name = "Brand 2", expectedRevision = themeAt).execute()
            assertThatThrownBy { UpdateTheme(theme, name = "Brand 3", expectedRevision = themeAt).execute() }.isInstanceOf(ResourceModifiedException::class.java)

            val stencil = StencilId(StencilKey.of("letterhead"), catalog)
            CreateStencil(stencil, "Letterhead", content = document("head")).execute()
            val stencilAt = GetStencil(stencil).query()!!.revision
            UpdateStencil(stencil, content = document("new head"), expectedRevision = stencilAt).execute()
            assertThatThrownBy { UpdateStencil(stencil, name = "Other", expectedRevision = stencilAt).execute() }.isInstanceOf(ResourceModifiedException::class.java)

            val codeList = CodeListId(CodeListKey.of("colours"), catalog)
            CreateCodeList(codeList, "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red"))).execute()
            val listAt = GetCodeList(codeList).query()!!.revision
            val withBlue = UpdateCodeList(codeList, "Colours", entries = listOf(CodeListEntry("red", "Red"), CodeListEntry("blue", "Blue")), expectedRevision = listAt).execute()!!
            assertThat(withBlue.revision).`as`("replacing the entries is a change of the list").isEqualTo(listAt + 1)
            assertThatThrownBy { UpdateCodeList(codeList, "Colours", entries = emptyList(), expectedRevision = listAt).execute() }.isInstanceOf(ResourceModifiedException::class.java)

            val attribute = AttributeId(AttributeKey.of("language"), catalog)
            CreateAttributeDefinition(attribute, "Language", allowedValues = listOf("nl")).execute()
            val attributeAt = GetAttributeDefinition(attribute).query()!!.revision
            UpdateAttributeDefinition(attribute, "Taal", allowedValues = listOf("nl"), expectedRevision = attributeAt).execute()
            assertThatThrownBy { UpdateAttributeDefinition(attribute, "Language", expectedRevision = attributeAt).execute() }.isInstanceOf(ResourceModifiedException::class.java)
        }
    }

    @Test
    fun `each draft's copy counts on its own`() {
        val catalog = catalog("rev-drafts")
        val variant = template(catalog)
        withMediator {
            val atNext = UpdateVariantContent(variant, document("next")).execute().revision
            val draft = CreateCatalogDraft(catalog.tenantKey, catalog.key, "Redesign").execute()
            val inDraft = CatalogVersionRef(catalog.tenantKey, catalog.key, DraftRef.Id(draft.draftId!!))

            assertThat(GetVariant(variant, catalogVersion = inDraft).query()!!.revision).`as`("a copy keeps the revision").isEqualTo(atNext)
            UpdateVariantContent(variant, document("redesign"), catalogVersion = inDraft, expectedRevision = atNext).execute()
            assertThat(UpdateVariantContent(variant, document("next 2"), expectedRevision = atNext).execute().revision)
                .`as`("the next version's row did not move").isEqualTo(atNext + 1)
        }
    }
}
