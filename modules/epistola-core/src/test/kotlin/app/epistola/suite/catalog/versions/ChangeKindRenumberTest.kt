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
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
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
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.stencilDocument
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Renumbering a draft to a lower kind of version than its changes against its base need (contract
 * ADR 0002, by the rule ADR 0001 sets for hotfixes) warns, listing every reason: breaking changes need
 * a major, additions at least a minor, and a fix fits a patch.
 */
class ChangeKindRenumberTest : IntegrationTestBase() {

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
     * and blue, released as 1.0.0; its draft is 1.0.1. With [dependency], it also depends on `shared`.
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

    private fun preview(tenant: TenantKey, to: String) = withMediator { PreviewCatalogVersionRenumber(tenant, letters, "1.0.1", to).query() }

    /** The warnings about the kind of version, each kind with its reasons. */
    private fun kindWarnings(tenant: TenantKey, to: String) = preview(tenant, to)
        .filter { it.kind in KINDS }
        .map { it.kind to it.reasons }

    @Test
    fun `an unchanged draft, or one with only fixes, is fine as a patch`() {
        val tenant = released("change-kind-none")
        assertThat(preview(tenant, "1.0.2")).isEmpty()

        withMediator { UpdateDocumentTemplate(invoice(tenant), name = "Invoice, renamed").execute() }

        assertThat(preview(tenant, "1.0.2")).isEmpty()
    }

    @Test
    fun `a removed resource is breaking, so a patch or a minor warns and a major does not`() {
        val tenant = released("change-kind-removed")
        withMediator { DeleteDocumentTemplate(invoice(tenant)).execute() }

        assertThat(kindWarnings(tenant, "1.0.2")).containsExactly("breaking-changes-in-patch" to listOf("template 'invoice': removed"))
        assertThat(kindWarnings(tenant, "1.1.0")).containsExactly("breaking-changes-in-minor" to listOf("template 'invoice': removed"))
        assertThat(kindWarnings(tenant, "2.0.0")).isEmpty()
        val warning = preview(tenant, "1.0.2").single()
        assertThat(warning.versions).containsExactly("1.0.0")
        assertThat(warning.text).contains("template 'invoice': removed")
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

        assertThat(kindWarnings(tenant, "1.1.0")).containsExactly(
            "breaking-changes-in-minor" to listOf("template 'invoice': data contract: \"name\" is now required"),
        )
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

        assertThat(kindWarnings(tenant, "1.0.2")).containsExactly(
            "breaking-changes-in-patch" to listOf(
                "stencil 'letterhead': placeholder 'body' removed",
                "stencil 'letterhead': parameter 'title' changed type from string to number",
                "stencil 'letterhead': placeholder 'content' added",
            ),
        )
    }

    @Test
    fun `a stencil parameter an instance must now bind is breaking`() {
        val tenant = released("change-kind-stencil-required")
        withMediator {
            UpdateStencil(letterhead(tenant), parameterSchema = schema("""{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}""")).execute()
        }

        assertThat(kindWarnings(tenant, "1.1.0")).containsExactly(
            "breaking-changes-in-minor" to listOf("stencil 'letterhead': parameter 'title' must now be bound"),
        )
    }

    @Test
    fun `an attribute value no longer allowed, or a code-list entry removed, is breaking`() {
        val tenant = released("change-kind-attribute")
        withMediator {
            UpdateAttributeDefinition(language(tenant), "Language", allowedValues = listOf("nl")).execute()
            UpdateCodeList(colours(tenant), "Colours", entries = listOf(CodeListEntry("red", "Red"))).execute()
        }

        assertThat(kindWarnings(tenant, "1.0.2")).containsExactly(
            "breaking-changes-in-patch" to listOf("attribute 'language': no longer allows en", "codeList 'colours': entry 'blue' removed"),
        )
    }

    @Test
    fun `a changed dependency range is breaking`() {
        val tenant = released("change-kind-dependency", dependency = true)
        withMediator {
            val shared = GetCatalog(tenant, CatalogKey.of("shared")).query()!!
            UpdateCatalogDependencies(tenant, letters, listOf(DeclaredCatalogDependency(shared.registry, shared.namespace, "shared", "~1.0.0"))).execute()
        }

        val warnings = kindWarnings(tenant, "1.1.0")
        assertThat(warnings.map { it.first }).containsExactly("breaking-changes-in-minor")
        assertThat(warnings.single().second.single()).endsWith("/shared': its range changed from ^1.0.0 to ~1.0.0")
    }

    @Test
    fun `an added resource or a widened data contract is additive, so a patch warns and a minor does not`() {
        val tenant = released("change-kind-additive")
        withMediator {
            CreateDocumentTemplate(TemplateId(TemplateKey.of("reminder"), catalogId(tenant)), "Reminder").execute()
            UpdateDataContract(invoice(tenant), dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"},"reference":{"type":"string"}}}""")).execute()
            UpdateAttributeDefinition(language(tenant), "Language", allowedValues = listOf("nl", "en", "de")).execute()
        }

        assertThat(kindWarnings(tenant, "1.0.2")).containsExactly(
            "additions-in-patch" to listOf(
                "attribute 'language': also allows de",
                "template 'invoice': its data contract accepts more input",
                "template 'reminder': added",
            ),
        )
        assertThat(kindWarnings(tenant, "1.1.0")).isEmpty()
    }

    @Test
    fun `renumbering to a version too low for the changes is refused until acknowledged`() {
        val tenant = released("change-kind-acknowledge")
        withMediator { DeleteDocumentTemplate(invoice(tenant)).execute() }

        assertThatThrownBy { withMediator { RenumberCatalogVersion(tenant, letters, "1.0.1", "1.0.2").execute() } }
            .isInstanceOf(RenumberWarningsNotAcknowledgedException::class.java)
            .hasMessageContaining("template 'invoice': removed")
            .satisfies({ assertThat((it as RenumberWarningsNotAcknowledgedException).warnings.map { w -> w.kind }).containsExactly("breaking-changes-in-patch") })

        val renumbered = withMediator { RenumberCatalogVersion(tenant, letters, "1.0.1", "1.0.2", acknowledgeWarnings = true).execute() }

        assertThat(renumbered.version).isEqualTo("1.0.2")
        assertThat(renumbered.base).isEqualTo("1.0.0")
        // Renumbered to a major, nothing is left to acknowledge.
        withMediator { RenumberCatalogVersion(tenant, letters, "1.0.2", "2.0.0").execute() }
    }

    private companion object {
        val KINDS = setOf("breaking-changes-in-patch", "breaking-changes-in-minor", "additions-in-patch")
    }
}
