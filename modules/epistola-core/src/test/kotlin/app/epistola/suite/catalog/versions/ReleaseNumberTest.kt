// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ResolveCatalogDependencies
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.dependencies.DeclareUsedCatalogs
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.stencilDocument
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The number a draft is released as (contract ADR 0002, amendment 2): numbered from the kind of change
 * it holds against the latest release, by the rule ADR 0001 sets for hotfixes. Breaking changes need a
 * major, additions a minor, and a fix fits a patch; the author may raise the number, never lower it.
 */
class ReleaseNumberTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val json = ObjectMapper()

    private fun catalogId(tenant: TenantKey) = CatalogId(letters, TenantId(tenant))
    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), catalogId(tenant))
    private fun letterhead(tenant: TenantKey) = StencilId(StencilKey.of("letterhead"), catalogId(tenant))
    private fun language(tenant: TenantKey) = AttributeId(AttributeKey.of("language"), catalogId(tenant))
    private fun colours(tenant: TenantKey) = CodeListId(CodeListKey.of("colours"), catalogId(tenant))

    private fun schema(value: String): ObjectNode = json.readValue(value, ObjectNode::class.java)

    private val contract = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")

    private fun stencilContent(placeholder: String) = stencilDocument {
        text("logo", "LOGO")
        placeholder("slot", placeholder)
        build("logo", "slot")
    }

    private val parameters = schema("""{"type":"object","properties":{"title":{"type":"string"}}}""")

    /**
     * `letters` with an invoice (a contract with an optional `name`), a letterhead stencil (placeholder
     * `body`, optional parameter `title`), an attribute allowing `nl` and `en`, and a code list of red
     * and blue, released as 1.0.0, its next version continuing on it. With [dependency], it also depends
     * on `shared`.
     */
    private fun released(name: String, dependency: Boolean = false): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        UpdateDataContract(invoice(tenant), dataModel = contract).execute()
        CreateStencil(letterhead(tenant), "Letterhead", content = stencilContent("body"), parameterSchema = parameters).execute()
        CreateAttributeDefinition(language(tenant), "Language", allowedValues = listOf("nl", "en")).execute()
        CreateCodeList(colours(tenant), "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red"), CodeListEntry("blue", "Blue"))).execute()
        if (dependency) {
            val shared = CatalogKey.of("shared")
            CreateCatalog(tenant, shared, "Shared").execute()
            mediator.releaseNext(CatalogId(shared, TenantId(tenant)))
            val catalog = GetCatalog(tenant, shared).query()!!
            UpdateCatalogDependencies(tenant, letters, listOf(DeclaredCatalogDependency(catalog.registry, catalog.namespace, "shared", "^1.0.0"))).execute()
        }
        mediator.releaseNext(catalogId(tenant))
        tenant
    }

    /** The number the next version would be released as, and why. */
    private fun assess(tenant: TenantKey) = withMediator { AssessDraftNumber(tenant, letters).query()!! }

    @Test
    fun `a catalog never released is released as 1_0_0, whatever it holds`() {
        val tenant = withMediator { createTenant("release-number-first").id }
        withMediator { CreateCatalog(tenant, letters, "Letters").execute() }

        val first = withMediator { AssessDraftNumber(tenant, letters).query()!! }

        assertThat(first.latest).isNull()
        assertThat(first.needs).isNull()
        assertThat(first.suggested).isEqualTo("1.0.0")
        assertThat(first.refuses("0.9.0")).`as`("any first number").isNull()
    }

    @Test
    fun `an unchanged next version, or one with only fixes, is the next patch`() {
        val tenant = released("change-kind-none")
        assertThat(assess(tenant).suggested).isEqualTo("1.0.1")

        withMediator { UpdateDocumentTemplate(invoice(tenant), name = "Invoice, renamed").execute() }

        assertThat(assess(tenant).needs).isEqualTo(ChangeKind.NONE)
        assertThat(assess(tenant).reasons).isEmpty()
        assertThat(assess(tenant).suggested).isEqualTo("1.0.1")
    }

    @Test
    fun `a removed resource is breaking, so it needs a major`() {
        val tenant = released("change-kind-removed")
        withMediator { DeleteDocumentTemplate(invoice(tenant)).execute() }

        val assessment = assess(tenant)
        assertThat(assessment.needs).isEqualTo(ChangeKind.BREAKING)
        assertThat(assessment.reasons).containsExactly("template 'invoice': removed")
        assertThat(assessment.suggested).isEqualTo("2.0.0")
        assertThat(assessment.refuses("1.0.1")).contains("needs at least 2.0.0").contains("template 'invoice': removed")
        assertThat(assessment.refuses("1.1.0")).isNotNull()
        assertThat(assessment.refuses("2.0.0")).isNull()
        assertThat(assessment.refuses("3.0.0")).`as`("a higher number is the author's choice").isNull()
    }

    @Test
    fun `a narrowed data contract is breaking`() {
        val tenant = released("change-kind-contract-narrowed")
        withMediator {
            UpdateDataContract(
                invoice(tenant),
                dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""),
                dataExamples = listOf(DataExample(id = "example-1", name = "Example 1", data = schema("""{"name":"Acme"}"""))),
            ).execute()
        }

        assertThat(assess(tenant).reasons).containsExactly("template 'invoice': data contract: \"name\" is now required")
        assertThat(assess(tenant).suggested).isEqualTo("2.0.0")
    }

    @Test
    fun `a stencil that lost a placeholder, or whose parameter changed type, is breaking`() {
        val tenant = released("change-kind-stencil")
        withMediator {
            UpdateStencil(
                letterhead(tenant),
                content = stencilContent("content"),
                parameterSchema = schema("""{"type":"object","properties":{"title":{"type":"number"}}}"""),
            ).execute()
        }

        assertThat(assess(tenant).reasons).containsExactly(
            "stencil 'letterhead': placeholder 'body' removed",
            "stencil 'letterhead': parameter 'title' changed type from string to number",
            "stencil 'letterhead': placeholder 'content' added",
        )
        assertThat(assess(tenant).suggested).isEqualTo("2.0.0")
    }

    @Test
    fun `a stencil parameter an instance must now bind is breaking`() {
        val tenant = released("change-kind-stencil-required")
        withMediator {
            UpdateStencil(letterhead(tenant), parameterSchema = schema("""{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}""")).execute()
        }

        assertThat(assess(tenant).reasons).containsExactly("stencil 'letterhead': parameter 'title' must now be bound")
        assertThat(assess(tenant).needs).isEqualTo(ChangeKind.BREAKING)
    }

    @Test
    fun `an attribute value no longer allowed, or a code-list entry removed, is breaking`() {
        val tenant = released("change-kind-attribute")
        withMediator {
            UpdateAttributeDefinition(language(tenant), "Language", allowedValues = listOf("nl")).execute()
            UpdateCodeList(colours(tenant), "Colours", entries = listOf(CodeListEntry("red", "Red"))).execute()
        }

        assertThat(assess(tenant).reasons).containsExactly("attribute 'language': no longer allows en", "codeList 'colours': entry 'blue' removed")
        assertThat(assess(tenant).suggested).isEqualTo("2.0.0")
    }

    @Test
    fun `a changed dependency range is no change to what others rely on`() {
        val tenant = released("change-kind-dependency", dependency = true)
        withMediator {
            val shared = GetCatalog(tenant, CatalogKey.of("shared")).query()!!
            UpdateCatalogDependencies(tenant, letters, listOf(DeclaredCatalogDependency(shared.registry, shared.namespace, "shared", "~1.0.0"))).execute()
        }

        assertThat(assess(tenant).needs).isEqualTo(ChangeKind.NONE)
        assertThat(assess(tenant).suggested).isEqualTo("1.0.1")
    }

    /**
     * `shared` (theme `brand`, stencil `footer` with placeholder `body`) released as 1.0.0, then
     * [change] released as 2.0.0; `letters` with an invoice on `brand`, filling `footer` when
     * [usesFooter], released as 1.0.0 on shared 1.0.0, then its lock moved to shared 2.0.0.
     */
    private fun lockMovedToNewMajor(name: String, usesFooter: Boolean, change: (TenantKey) -> Unit): TenantKey = withMediator {
        val tenant = createTenant(name).id
        val shared = CatalogKey.of("shared")
        val sharedId = CatalogId(shared, TenantId(tenant))
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), sharedId), "Brand").execute()
        CreateStencil(StencilId(StencilKey.of("footer"), sharedId), "Footer", content = stencilContent("body")).execute()
        mediator.releaseNext(sharedId)
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute()
        if (usesFooter) {
            UpdateVariantContent(
                VariantId(VariantKey.INITIAL, invoice(tenant)),
                stencilDocument {
                    text("signed", "Signed")
                    placeholder("fill", "body", fill = listOf("signed"))
                    stencil("footer-1", "footer", fills = listOf("fill"), catalogKey = "shared")
                    build("footer-1")
                },
            ).execute()
        }
        DeclareUsedCatalogs(tenant, letters).execute()
        mediator.releaseNext(catalogId(tenant))
        change(tenant)
        MarkCatalogReady(tenant, shared).execute()
        ReleaseCatalogVersion(tenant, shared, "2.0.0").execute()
        ResolveCatalogDependencies(tenant, letters).execute()
        tenant
    }

    @Test
    fun `a dependency's new major that breaks only what is not used here is a fix`() {
        val tenant = lockMovedToNewMajor("change-kind-lock-fits", usesFooter = false) { t ->
            DeleteStencil(StencilId(StencilKey.of("footer"), CatalogId(CatalogKey.of("shared"), TenantId(t)))).execute()
        }

        val assessment = assess(tenant)

        assertThat(assessment.needs).`as`("Footer is not used here").isEqualTo(ChangeKind.NONE)
        assertThat(assessment.suggested).isEqualTo("1.0.1")
    }

    @Test
    fun `a dependency's new version that breaks what is used here is breaking, naming what`() {
        val tenant = lockMovedToNewMajor("change-kind-lock-breaks", usesFooter = true) { t ->
            UpdateStencil(StencilId(StencilKey.of("footer"), CatalogId(CatalogKey.of("shared"), TenantId(t))), content = stencilContent("content")).execute()
        }

        val assessment = assess(tenant)

        assertThat(assessment.needs).isEqualTo(ChangeKind.BREAKING)
        assertThat(assessment.reasons.single()).endsWith("/shared': v2.0.0 breaks what is used of it: stencil 'footer': placeholder 'body' removed")
        assertThat(assessment.suggested).isEqualTo("2.0.0")
    }

    @Test
    fun `an added resource or a widened data contract is additive, so it needs a minor`() {
        val tenant = released("change-kind-additive")
        withMediator {
            CreateDocumentTemplate(TemplateId(TemplateKey.of("reminder"), catalogId(tenant)), "Reminder").execute()
            UpdateDataContract(invoice(tenant), dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"reference":{"type":"string"}}}""")).execute()
            UpdateAttributeDefinition(language(tenant), "Language", allowedValues = listOf("nl", "en", "de")).execute()
        }

        val assessment = assess(tenant)
        assertThat(assessment.needs).isEqualTo(ChangeKind.ADDITIVE)
        assertThat(assessment.reasons).containsExactly(
            "attribute 'language': also allows de",
            "template 'invoice': its data contract accepts more input",
            "template 'reminder': added",
        )
        assertThat(assessment.suggested).isEqualTo("1.1.0")
        assertThat(assessment.refuses("1.0.1")).contains("needs at least 1.1.0")
        assertThat(assessment.refuses("1.1.0")).isNull()
    }

    @Test
    fun `a catalog used but not declared counts as declared, since publishing declares it first`() {
        val tenant = released("change-kind-undeclared")
        withMediator {
            val shared = CatalogKey.of("shared")
            CreateCatalog(tenant, shared, "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant))), "Brand").execute()
            mediator.releaseNext(CatalogId(shared, TenantId(tenant)))
            UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute()
        }
        assertThat(withMediator { ListCatalogDependencies(tenant, letters).query() }).`as`("not declared yet").isEmpty()

        val assessment = assess(tenant)

        // The number offered is the one publishing then accepts, after it declares Shared: a dependency
        // added is an addition, not a break.
        assertThat(assessment.reasons.single { it.contains("shared") }).contains("added")
        assertThat(assessment.needs).isEqualTo(ChangeKind.ADDITIVE)
        assertThat(assessment.suggested).isEqualTo("1.1.0")
        withMediator {
            DeclareUsedCatalogs(tenant, letters).execute()
            MarkCatalogReady(tenant, letters).execute()
        }
        assertThat(withMediator { ReleaseCatalogVersion(tenant, letters, assessment.suggested).execute() }.version).isEqualTo("1.1.0")
    }

    @Test
    fun `releasing numbers the next version from its changes, refuses a number too low or taken, and takes a higher one`() {
        val tenant = released("change-kind-release")
        withMediator {
            DeleteDocumentTemplate(invoice(tenant)).execute()
            MarkCatalogReady(tenant, letters).execute()
        }

        assertThatThrownBy { withMediator { ReleaseCatalogVersion(tenant, letters, "1.0.1").execute() } }
            .isInstanceOf(CatalogReleaseVersionException::class.java)
            .hasMessageContaining("needs at least 2.0.0")
            .hasMessageContaining("template 'invoice': removed")
        assertThatThrownBy { withMediator { ReleaseCatalogVersion(tenant, letters, "1.0.0").execute() } }
            .isInstanceOf(CatalogReleaseVersionException::class.java)
            .hasMessageContaining("released already")

        assertThat(withMediator { ReleaseCatalogVersion(tenant, letters).execute() }.version).isEqualTo("2.0.0")

        // Nothing changed since: a patch, unless the author asks for more.
        withMediator { MarkCatalogReady(tenant, letters).execute() }
        assertThat(withMediator { ReleaseCatalogVersion(tenant, letters, "3.0.0").execute() }.version).isEqualTo("3.0.0")
        assertThat(assess(tenant).latest).isEqualTo("3.0.0")
        assertThat(assess(tenant).suggested).isEqualTo("3.0.1")
    }
}
