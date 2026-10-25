// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.GetTenantResourceGraph
import app.epistola.suite.catalog.graph.ReferenceResolution
import app.epistola.suite.catalog.graph.ReferenceSelector
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.ResourceIdentity
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
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.queries.CountDocuments
import app.epistola.suite.documents.queries.ListDocuments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilUsagePage
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.templateJoin
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testAddress
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class CatalogResourceRelocationIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `moves a stencil atomically, re-pointing the variant content that names it`() {
        val tenant = createTenant("Move stencil")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = testCatalog(tenantId, sourceCatalog)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val sourceAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(variantId, templateEmbedding(stencilId.key.value)).execute()
            MarkVariantReady(variantId).execute()
        }

        val before = identityAt(tenant.id, sourceAddress)
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }

        assertThat(preview.executable).isTrue()
        assertThat(preview.mutableRewriteCount).isEqualTo(1)
        assertThat(preview.immutableReferenceCount).isEqualTo(0)

        withMediator {
            MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), preview.planFingerprint).execute()
        }

        // A move leaves nothing behind: the identity lives at the new address only.
        assertThat(identityAt(tenant.id, sourceAddress.copy(catalogKey = targetCatalog.value))).isEqualTo(before)
        assertThat(identityAt(tenant.id, sourceAddress)).isNull()

        val movedStencilId = StencilId(stencilId.key, testCatalog(tenantId, targetCatalog))
        assertThat(withMediator { GetStencil(movedStencilId).query() }).isNotNull()

        val content = withMediator { GetVariantContent(variantId).query()!! }
        assertThat(content.nodes.getValue("stencil-instance").props?.get("catalogKey"))
            .isEqualTo(targetCatalog.value)

        // Nothing names the vacated address any more: a variant has one working model, re-pointed.
        val graph = withMediator { GetTenantResourceGraph(tenant.id, includeHistory = true).query() }
        assertThat(graph.edges)
            .filteredOn { it.targetSelector == ReferenceSelector(CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value) }
            .isEmpty()

        withMediator { DeleteStencil(movedStencilId, force = true).execute() }
        assertThat(identityAt(tenant.id, sourceAddress.copy(catalogKey = targetCatalog.value))).isNull()
    }

    @Test
    fun `execute rejects a stale preview after a draft changes`() {
        val tenant = createTenant("Stale stencil move")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = testCatalog(tenantId, sourceCatalog)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val sourceAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            CreateDocumentTemplate(templateId, "Invoice").execute()
            UpdateVariantContent(variantId, templateEmbedding(stencilId.key.value)).execute()
        }
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }

        withMediator { UpdateVariantContent(variantId, emptyTemplate()).execute() }

        assertThatThrownBy {
            withMediator {
                MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), preview.planFingerprint).execute()
            }
        }.isInstanceOf(StaleCatalogResourceMovePlanException::class.java)

        assertThat(identityAt(tenant.id, sourceAddress)).isNotNull()
    }

    @Test
    fun `the address a relocated resource left behind can be reused at once`() {
        val tenant = createTenant("Reused address")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val stencilId = StencilId(StencilKey.of("header"), testCatalog(tenantId, sourceCatalog))
        val sourceAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
        }
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // No alias is left to reserve it: anything still naming letters/header now means the
        // replacement, which is the crude move's accepted cost.
        withMediator { CreateStencil(stencilId, "Replacement").execute() }

        val replacement = identityAt(tenant.id, sourceAddress)
        assertThat(replacement).isNotNull()
        assertThat(replacement).isNotEqualTo(identityAt(tenant.id, sourceAddress.copy(catalogKey = targetCatalog.value)))
    }

    @Test
    fun `a resource can be moved back to a catalog it previously occupied`() {
        val tenant = createTenant("Move stencil back")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val stencilId = StencilId(StencilKey.of("header"), testCatalog(tenantId, sourceCatalog))
        val sourceAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)
        val movedAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, targetCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
        }
        val identity = identityAt(tenant.id, sourceAddress)

        val out = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), out.planFingerprint).execute() }

        val back = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(movedAddress.movedTo(sourceCatalog))).query() }
        assertThat(back.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, listOf(movedAddress.movedTo(sourceCatalog)), back.planFingerprint).execute() }

        assertThat(identityAt(tenant.id, sourceAddress)).isEqualTo(identity)
        assertThat(identityAt(tenant.id, movedAddress)).isNull()
    }

    @Test
    fun `an unrelated edit elsewhere in the tenant does not invalidate the plan`() {
        val tenant = createTenant("Unrelated edit")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = testCatalog(tenantId, sourceCatalog)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val referencingVariant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), sourceCatalogId))
        val unrelatedVariant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("memo"), sourceCatalogId))
        val sourceAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), sourceCatalogId), "Invoice").execute()
            UpdateVariantContent(referencingVariant, templateEmbedding(stencilId.key.value)).execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("memo"), sourceCatalogId), "Memo").execute()
            UpdateVariantContent(unrelatedVariant, emptyTemplate()).execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }
        assertThat(preview.mutableRewriteCount).isEqualTo(1)

        // A draft that has nothing to do with the moving stencil changes in the meantime.
        withMediator { UpdateVariantContent(unrelatedVariant, templateEmbedding("unrelated-stencil")).execute() }

        withMediator { MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        assertThat(identityAt(tenant.id, sourceAddress.copy(catalogKey = targetCatalog.value))).isNotNull()
    }

    @Test
    fun `write-time qualification lets a stencil with published dependencies move`() {
        val tenant = createTenant("Qualified on write")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = testCatalog(tenantId, sourceCatalog)
        val header = StencilId(StencilKey.of("header"), sourceCatalogId)
        val headerAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, sourceCatalog.value, header.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), sourceCatalogId), "Brand").execute()
            CreateStencil(header, "Header").execute()
            // Authored WITHOUT a catalogKey: before write-time qualification this stored a relative
            // reference, which a move could not preserve once published.
            UpdateStencil(header, content = dependsOnThemeRelatively()).execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(headerAddress.movedTo(targetCatalog))).query() }

        assertThat(preview.blockers).isEmpty()

        withMediator { MoveCatalogResources(tenant.id, listOf(headerAddress.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // The published dependency still names the catalog the theme actually lives in.
        val graph = withMediator { GetTenantResourceGraph(tenant.id, includeHistory = true).query() }
        assertThat(graph.edges)
            .filteredOn { it.targetSelector.type == CatalogResourceType.THEME }
            .allSatisfy { assertThat(it.target?.catalogKey).isEqualTo(sourceCatalog.value) }
    }

    @Test
    fun `a stencil whose content predates qualification can still move`() {
        val tenant = createTenant("Legacy relative stencil")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = testCatalog(tenantId, sourceCatalog)
        val header = StencilId(StencilKey.of("header"), sourceCatalogId)
        val headerAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, sourceCatalog.value, header.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), sourceCatalogId), "Brand").execute()
            CreateStencil(header, "Header").execute()
            UpdateStencil(header, content = dependsOnThemeRelatively()).execute()
        }
        // Content written before references were qualified on write stored this relatively, and the
        // 2.0 upgrade carried it into the working copy. No command writes that shape any more, so the
        // qualification is stripped from the row.
        jdbi.useHandle<Exception> { handle ->
            val stripped = handle.createUpdate(
                "UPDATE stencils SET content = content #- '{themeRef,catalogKey}' WHERE tenant_key = :t AND id = :ownerKey" +
                    " AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = :catalogKey)",
            )
                .bind("t", tenant.id).bind("catalogKey", sourceCatalog).bind("ownerKey", header.key.value)
                .execute()
            assertThat(stripped).isEqualTo(1)
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(headerAddress.movedTo(targetCatalog))).query() }
        assertThat(preview.blockers).isEmpty()
        assertThat(preview.relocations.single().mutableRewriteCount).isEqualTo(1)

        withMediator { MoveCatalogResources(tenant.id, listOf(headerAddress.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // The stencil now says what it always meant: the theme in the catalog it left.
        val moved = withMediator { GetStencil(StencilId(header.key, testCatalog(tenantId, targetCatalog))).query()!! }
        assertThat((moved.content.themeRef as ThemeRefOverride).catalogKey).isEqualTo(sourceCatalog.value)
        val graph = withMediator { GetTenantResourceGraph(tenant.id, includeHistory = true).query() }
        assertThat(graph.edges)
            .filteredOn { it.targetSelector.type == CatalogResourceType.THEME }
            .isNotEmpty
            .allSatisfy { assertThat(it.target?.catalogKey).isEqualTo(sourceCatalog.value) }
    }

    @Test
    fun `a template's legacy relative references keep their meaning when it moves`() {
        val tenant = createTenant("Legacy relative template")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = testCatalog(tenantId, sourceCatalog)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val address = testAddress(tenant.id, CatalogResourceType.TEMPLATE, sourceCatalog.value, templateId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(variantId, templateEmbedding(stencilId.key.value)).execute()
            MarkVariantReady(variantId).execute()
        }
        // Content written before references were qualified on write stored this relatively, and the
        // 2.0 upgrade carried it into the variant's working model. No command writes that shape any
        // more, so the qualification is stripped from the row.
        stripQualification("template_variants", "template_model", "{nodes,stencil-instance,props,catalogKey}", tenant.id, sourceCatalog, templateOwner, templateId.key.value)

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        assertThat(preview.blockers).isEmpty()
        assertThat(preview.relocations.single().mutableRewriteCount).isEqualTo(1)
        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // Relative meant "letters" when it was written. After the move that is spelled out, so the
        // moved variant does not resolve against "shared", and it can still be marked ready.
        val movedVariant = VariantId(VariantKey.INITIAL, TemplateId(templateId.key, testCatalog(tenantId, targetCatalog)))
        val moved = withMediator { GetVariantContent(movedVariant).query()!! }
        assertThat(moved.nodes.getValue("stencil-instance").props?.get("catalogKey")).isEqualTo(sourceCatalog.value)
        withMediator { MarkVariantReady(movedVariant).execute() }
    }

    /**
     * How a versions table names the template that owns it, as a bindable predicate on
     * `(:tenantKey, :catalogKey, :ownerKey)`.
     */
    private val templateOwner =
        "template_resource_id = (SELECT resource_id FROM document_templates " +
            "WHERE tenant_key = :tenantKey AND catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND id = :ownerKey)"

    /** Removes a qualification the write path added, to plant content in its pre-qualification shape. */
    private fun stripQualification(table: String, column: String, path: String, tenantKey: TenantKey, catalogKey: CatalogKey, owner: String, ownerKey: String) {
        jdbi.useHandle<Exception> { handle ->
            val stripped = handle.createUpdate(
                "UPDATE $table SET $column = $column #- :path::text[] WHERE tenant_key = :tenantKey AND $owner",
            )
                .bind("path", path)
                .bind("tenantKey", tenantKey)
                .bindCatalog("catalogKey", testCatalog(tenantKey, catalogKey))
                .bind("ownerKey", ownerKey)
                .execute()
            assertThat(stripped).isEqualTo(1)
        }
    }

    private fun dependsOnThemeRelatively(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
        themeRef = ThemeRefOverride(themeKey = "brand"),
    )

    @Test
    fun `moves an attribute and repoints the variants that name it`() {
        val tenant = createTenant("Move attribute")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = testCatalog(tenantId, sourceCatalog)
        val attribute = AttributeId(AttributeKey.of("brand"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val address = testAddress(tenant.id, CatalogResourceType.ATTRIBUTE, sourceCatalog.value, attribute.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateAttributeDefinition(attribute, "Brand", allowedValues = listOf("acme")).execute()
            CreateDocumentTemplate(templateId, "Invoice").execute()
            UpdateVariant(variantId, "Main", mapOf("letters.brand" to "acme")).execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }

        // Every reference to an attribute is a mutable variant key, so none is left behind.
        assertThat(preview.blockers).isEmpty()
        assertThat(preview.mutableRewriteCount).isEqualTo(1)
        assertThat(preview.immutableReferenceCount).isZero()

        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        assertThat(identityAt(tenant.id, address.copy(catalogKey = targetCatalog.value))).isNotNull()

        // The variant now names the attribute at its new address, with its value intact.
        assertThat(variantAttributes(tenant.id, templateId, variantId))
            .containsExactlyEntriesOf(mapOf("shared.brand" to "acme"))
    }

    /**
     * Every catalog resource type is now movable, so `unsupported-resource-type` has no subject
     * left to fire on. The blocker stays as the answer for a type added to [CatalogResourceType]
     * before it is registered in [MovableResource]; this asserts the set is currently complete,
     * which is what makes that blocker unreachable rather than merely unused.
     */
    @Test
    fun `every catalog resource type is relocatable`() {
        assertThat(CatalogResourceType.entries.filter { MovableResource.of(it) == null })
            .describedAs("a type with no MovableResource entry would be blocked as unsupported")
            .isEmpty()
    }

    @Test
    fun `moves a template with its hierarchy while generation history keeps its original address`() {
        val tenant = createTenant("Move template")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val templateId = TemplateId(TemplateKey.of("invoice"), testCatalog(tenantId, sourceCatalog))
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val address = testAddress(tenant.id, CatalogResourceType.TEMPLATE, sourceCatalog.value, templateId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(variantId, emptyTemplate()).execute()
            MarkVariantReady(variantId).execute()
        }

        // A generation record predating the move: it must survive unchanged.
        plantGenerationRecord(tenant.id, sourceCatalog, templateId.key, variantId.key)

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        assertThat(preview.blockers).isEmpty()

        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // The template and its owned hierarchy followed.
        assertThat(identityAt(tenant.id, address.copy(catalogKey = targetCatalog.value))).isNotNull()
        // The hierarchy no longer stores a catalog of its own: it follows the template's, which is
        // the point of the re-key, so reading it back through the template is the real assertion.
        assertThat(catalogKeysOfTemplatesOwning("template_variants", tenant.id)).containsExactly(targetCatalog.value)

        // Generation history did not: it records where the template lived at the time.
        assertThat(catalogKeysIn("documents", tenant.id)).containsExactly(sourceCatalog.value)

        // The link back to the template is by identity, so it survives the move that the address
        // deliberately does not follow.
        val templateIdentity = identityAt(tenant.id, address.copy(catalogKey = targetCatalog.value))
        assertThat(documentTemplateIdentities(tenant.id)).containsExactly(templateIdentity)
    }

    /**
     * Planted directly: no command produces a generation record against an arbitrary historical
     * address, and the insert trigger fills `template_resource_id` from the template at that address.
     */
    private fun plantGenerationRecord(tenantKey: TenantKey, catalogKey: CatalogKey, templateKey: TemplateKey, variantKey: VariantKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO documents (id, tenant_key, catalog_key, registry, namespace, template_key, variant_key, version_key,
                                       filename, size_bytes, created_at)
                VALUES (gen_random_uuid(), :tenantKey, :catalogKey,
                        (SELECT registry FROM catalogs WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey),
                        (SELECT namespace FROM catalogs WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey),
                        :templateKey, :variantKey, 1,
                        'invoice.pdf', 1024, NOW())
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("catalogKey", catalogKey)
                .bind("templateKey", templateKey)
                .bind("variantKey", variantKey)
                .execute()
        }
    }

    private fun documentTemplateIdentities(tenantKey: TenantKey): List<ResourceIdentity> = jdbi.withHandle<List<ResourceIdentity>, Exception> { handle ->
        handle.createQuery("SELECT DISTINCT template_resource_id FROM documents WHERE tenant_key = :tenantKey")
            .bind("tenantKey", tenantKey)
            .map { rs, _ -> ResourceIdentity.of(rs.getString("template_resource_id")) }
            .list()
    }

    private fun catalogKeysIn(table: String, tenantKey: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT DISTINCT catalog_key::text FROM $table WHERE tenant_key = :tenantKey")
            .bind("tenantKey", tenantKey)
            .mapTo(String::class.java)
            .list()
    }

    /** Where the templates owning every row of [table] live. Only the table name is interpolated. */
    private fun catalogKeysOfTemplatesOwning(table: String, tenantKey: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT DISTINCT template_catalog.catalog_key::text
            FROM $table owned
            ${templateJoin("owned")}
            JOIN catalogs template_catalog
              ON template_catalog.tenant_key = template.tenant_key AND template_catalog.catalog_id = template.catalog_id
            WHERE owned.tenant_key = :tenantKey
            """,
        )
            .bind("tenantKey", tenantKey)
            .mapTo(String::class.java)
            .list()
    }

    @Test
    fun `generation history follows a renamed template by identity`() {
        val tenant = createTenant("History after rename")
        val tenantId = TenantId(tenant.id)
        val catalog = CatalogKey.of("letters")
        val templateId = TemplateId(TemplateKey.of("invoice"), testCatalog(tenantId, catalog))
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val address = testAddress(tenant.id, CatalogResourceType.TEMPLATE, catalog.value, templateId.key.value)

        withMediator {
            CreateCatalog(tenant.id, catalog, "Letters").execute()
            CreateDocumentTemplate(templateId, "Invoice").execute()
        }
        plantGenerationRecord(tenant.id, catalog, templateId.key, variantId.key)

        val renamed = address.renamedTo("invoice-v2")
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(renamed)).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(renamed), preview.planFingerprint).execute() }

        // The record keeps the key it was generated under, so a lookup by address would miss it;
        // the identity the insert trigger recorded still ties it to the template.
        val newKey = TemplateKey.of("invoice-v2")
        assertThat(withMediator { ListDocuments(tenant.id, templateId = newKey).query() }).hasSize(1)
        assertThat(withMediator { CountDocuments(tenant.id, templateId = newKey).query() }).isEqualTo(1L)
    }

    @Test
    fun `usage reports templates that reference a moved stencil`() {
        val tenant = createTenant("Usage after move")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = testCatalog(tenantId, sourceCatalog)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val address = testAddress(tenant.id, CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(variantId, templateEmbedding(stencilId.key.value)).execute()
            MarkVariantReady(variantId).execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // The move re-pointed the variant, so asking the moved stencil what uses it surfaces it.
        val movedStencil = StencilId(stencilId.key, testCatalog(tenantId, targetCatalog))
        val usage = withMediator { GetStencilUsagePage(movedStencil).query() }

        assertThat(usage.items).isNotEmpty()
        assertThat(usage.items).anySatisfy { item ->
            assertThat(item.templateId).isEqualTo(templateId.key)
        }
    }

    @Test
    fun `a move that would make two catalogs depend on each other is blocked`() {
        val tenant = createTenant("Cycle guard")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")
        val lettersId = testCatalog(tenantId, letters)
        val sharedId = testCatalog(tenantId, shared)

        // letters/invoice already depends on shared/base-theme, so shared must install first.
        val sharedTheme = ThemeId(ThemeKey.of("base"), sharedId)
        val header = StencilId(StencilKey.of("header"), lettersId)
        val lettersTheme = ThemeId(ThemeKey.of("brand"), lettersId)
        val templateId = TemplateId(TemplateKey.of("invoice"), lettersId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            CreateTheme(sharedTheme, "Base").execute()
            CreateTheme(lettersTheme, "Brand").execute()
            CreateStencil(header, "Header").execute()
            // The stencil depends on a theme that stays in letters.
            UpdateStencil(header, content = usesTheme(lettersTheme.key.value, letters.value)).execute()
            // And a template in letters depends on the shared theme, so letters -> shared exists.
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateVariantContent(variantId, usesTheme(sharedTheme.key.value, shared.value)).execute()
        }

        // Moving the stencil into shared would make shared depend on letters, closing the loop.
        val preview = withMediator {
            PreviewCatalogResourceMove(tenant.id, listOf(testAddress(tenant.id, CatalogResourceType.STENCIL, letters.value, header.key.value).movedTo(shared))).query()
        }

        assertThat(preview.executable).isFalse()
        assertThat(preview.blockers).anySatisfy { blocker ->
            assertThat(blocker.code).isEqualTo("catalog-dependency-cycle")
            assertThat(blocker.message).contains("letters", "shared", "unrestorable")
        }
    }

    private fun usesTheme(themeKey: String, catalogKey: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
        themeRef = ThemeRefOverride(themeKey = themeKey, catalogKey = catalogKey),
    )

    @Test
    fun `a released catalog warns rather than blocking the move`() {
        val tenant = createTenant("Released warns")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val stencilId = StencilId(StencilKey.of("header"), testCatalog(tenantId, sourceCatalog))
        val address = testAddress(tenant.id, CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            MarkCatalogReady(testCatalog(tenant.id, sourceCatalog)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, sourceCatalog), "1.0.0", publication = ReleasePublication.SKIP).execute()
        }

        // A subscriber cannot follow the move, but that is the operator's judgement, not something
        // the suite can decide for them.
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        assertThat(preview.blockers).isEmpty()
        assertThat(preview.executable).isTrue()
        assertThat(preview.warnings).anySatisfy { assertThat(it.code).isEqualTo("released-source") }

        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }
        assertThat(identityAt(tenant.id, address.copy(catalogKey = targetCatalog.value))).isNotNull()
    }

    @Test
    fun `moving a code list carries its entries and the attributes bound to it`() {
        val tenant = createTenant("Move code list")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = testCatalog(tenantId, sourceCatalog)
        val codeListId = CodeListId(CodeListKey.of("languages"), sourceCatalogId)
        val attributeId = AttributeId(AttributeKey.of("language"), sourceCatalogId)
        val address = testAddress(tenant.id, CatalogResourceType.CODE_LIST, sourceCatalog.value, codeListId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateCodeList(
                codeListId,
                displayName = "Languages",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("nl", "Nederlands"), CodeListEntry("en", "English")),
            ).execute()
            // The binding is deliberately from a different catalog: it is the cross-catalog case
            // that a move has to keep pointing at the right place.
            CreateAttributeDefinition(attributeId, "Language", codeListId = codeListId).execute()
        }

        val authorBefore = attributeAuthor(tenant.id, sourceCatalog)
        assertThat(authorBefore).`as`("the attribute was saved by someone").isNotNull()

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        assertThat(preview.blockers).isEmpty()
        // The attribute's content names the list by alias and key (ADR 0033, step 3): one rewrite.
        assertThat(preview.mutableRewriteCount).isEqualTo(1)

        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        assertThat(identityAt(tenant.id, address.copy(catalogKey = targetCatalog.value))).isNotNull()
        // The entries are the list's content and move with it; the binding was rewritten to the new address.
        assertThat(entriesOwnedBy(tenant.id, targetCatalog, codeListId.key.value)).containsExactly("en", "nl")
        assertThat(boundCodeListCatalog(tenant.id, attributeId)).isEqualTo(targetCatalog.value)
        assertThat(attributeAuthor(tenant.id, sourceCatalog)).`as`("a rewrite is not an edit: whoever edited it last stays").isEqualTo(authorBefore)
    }

    private fun attributeAuthor(tenantKey: TenantKey, catalogKey: CatalogKey): java.util.UUID? = jdbi.withHandle<java.util.UUID?, Exception> { handle ->
        handle.createQuery("SELECT updated_by FROM current_attributes WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND id = 'language'")
            .bind("t", tenantKey).bindCatalog("c", testCatalog(tenantKey, catalogKey))
            .mapTo(java.util.UUID::class.java).findOne().orElse(null)
    }

    /** The codes owned by the code list now at this address, reached through its identity. */
    private fun entriesOwnedBy(tenantKey: TenantKey, catalogKey: CatalogKey, slug: String): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT e ->> 'code'
            FROM current_code_lists lists
            CROSS JOIN LATERAL jsonb_array_elements(lists.entries) e
            WHERE lists.tenant_key = :tenantKey AND lists.catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND lists.id = :slug
            ORDER BY 1
            """,
        )
            .bind("tenantKey", tenantKey)
            .bindCatalog("catalogKey", testCatalog(tenantKey, catalogKey))
            .bind("slug", slug)
            .mapTo(String::class.java)
            .list()
    }

    private fun boundCodeListCatalog(tenantKey: TenantKey, attributeId: AttributeId): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT list_catalog.catalog_key::text
            FROM current_attributes a
            JOIN catalogs list_catalog ON list_catalog.tenant_key = a.tenant_key AND list_catalog.catalog_id = a.code_list_catalog_id
            WHERE a.tenant_key = :tenantKey AND a.catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND a.id = :id
            """,
        )
            .bind("tenantKey", tenantKey)
            .bindCatalog("catalogKey", testCatalog(tenantKey, attributeId.catalogKey))
            .bind("id", attributeId.key)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }

    @Test
    fun `a relocation can rename as well as move`() {
        val tenant = createTenant("Rename on move")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")
        val stencilId = StencilId(StencilKey.of("header"), testCatalog(tenantId, letters))
        val address = testAddress(tenant.id, CatalogResourceType.STENCIL, letters.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
        }

        val relocation = address.movedTo(shared, "letterhead")
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(relocation)).query() }
        assertThat(preview.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, listOf(relocation), preview.planFingerprint).execute() }

        // Both halves of the address changed, and the old one is left empty.
        assertThat(identityAt(tenant.id, testAddress(tenant.id, CatalogResourceType.STENCIL, shared.value, "letterhead"))).isNotNull()
        assertThat(identityAt(tenant.id, address)).isNull()

        // The owned hierarchy followed the rename, not only the catalog change: the ON UPDATE
        // CASCADE foreign key fires on any referenced column.
        val renamed = StencilId(StencilKey.of("letterhead"), testCatalog(tenantId, shared))
        assertThat(withMediator { GetStencil(renamed).query() }).isNotNull()
    }

    @Test
    fun `a member can take an address another member is vacating`() {
        val tenant = createTenant("Handover batch")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")
        val header = StencilId(StencilKey.of("header"), testCatalog(tenantId, letters))
        val draft = StencilId(StencilKey.of("draft-header"), testCatalog(tenantId, letters))
        val headerAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, letters.value, "header")
        val draftAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, letters.value, "draft-header")

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            CreateStencil(header, "Header").execute()
            CreateStencil(draft, "Draft header").execute()
        }

        // Promote the draft into the name the old one is vacating. Neither move works alone: the
        // rename is blocked while the original still holds the address.
        val batch = listOf(headerAddress.movedTo(shared), draftAddress.renamedTo("header"))
        val promotedIdentity = identityAt(tenant.id, draftAddress)
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, batch).query() }

        assertThat(preview.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, batch, preview.planFingerprint).execute() }

        // letters/header is now the promoted resource, not the one that left.
        assertThat(identityAt(tenant.id, headerAddress)).isEqualTo(promotedIdentity)
        assertThat(identityAt(tenant.id, draftAddress)).isNull()
    }

    @Test
    fun `two resources exchanging addresses is refused rather than half applied`() {
        val tenant = createTenant("Swap refused")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val header = StencilId(StencilKey.of("header"), testCatalog(tenantId, letters))
        val footer = StencilId(StencilKey.of("footer"), testCatalog(tenantId, letters))
        val headerAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, letters.value, "header")
        val footerAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, letters.value, "footer")

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateStencil(header, "Header").execute()
            CreateStencil(footer, "Footer").execute()
        }

        // Each wants the other's address. Updates apply one at a time and address uniqueness is
        // checked per statement, so no order avoids a transient collision. Refused in the preview
        // rather than failing partway through the transaction.
        val batch = listOf(headerAddress.renamedTo("footer"), footerAddress.renamedTo("header"))
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, batch).query() }

        assertThat(preview.executable).isFalse()
        assertThat(preview.blockers).anySatisfy { blocker ->
            assertThat(blocker.code).isEqualTo("address-swap-cycle")
        }
    }

    @Test
    fun `one blocked member stops the whole batch`() {
        val tenant = createTenant("Batch atomicity")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")
        val good = StencilId(StencilKey.of("header"), testCatalog(tenantId, letters))
        val goodAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, letters.value, "header")
        val missingAddress = testAddress(tenant.id, CatalogResourceType.STENCIL, letters.value, "does-not-exist")

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            CreateStencil(good, "Header").execute()
        }

        val batch = listOf(goodAddress.movedTo(shared), missingAddress.movedTo(shared))
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, batch).query() }

        assertThat(preview.executable).isFalse()
        // The blocker names which member it belongs to, so a batch can be shown per row.
        assertThat(preview.blockers).anySatisfy { blocker ->
            assertThat(blocker.code).isEqualTo("resource-not-found")
            assertThat(blocker.source).isEqualTo(missingAddress)
        }

        assertThatThrownBy {
            withMediator { MoveCatalogResources(tenant.id, batch, preview.planFingerprint).execute() }
        }.isInstanceOf(CatalogResourceMoveBlockedException::class.java)

        // The member that could have moved did not: a batch is all or nothing.
        assertThat(identityAt(tenant.id, goodAddress)).isNotNull()
    }

    private fun variantAttributes(tenantKey: TenantKey, templateId: TemplateId, variantId: VariantId): Map<String, String> = jdbi.withHandle<Map<String, String>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT variants.attributes::text FROM template_variants variants
                ${templateJoin("variants")}
                WHERE variants.tenant_key = :tenantKey AND template.id = :templateKey AND variants.id = :variantKey
                """,
        )
            .bind("tenantKey", tenantKey)
            .bind("templateKey", templateId.key)
            .bind("variantKey", variantId.key)
            .mapTo(String::class.java)
            .one()
            .let { json ->
                (objectMapper.readTree(json) as ObjectNode)
                    .properties()
                    .associate { (key, value) -> key to value.stringValue() }
            }
    }

    /** The identity registered at [address] right now, or null when nothing occupies it. */
    private fun identityAt(tenantKey: TenantKey, address: ResourceAddress): ResourceIdentity? = jdbi.withHandle<ResourceIdentity?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT resource_id FROM catalog_resources
            WHERE tenant_key = :tenantKey AND resource_type = :resourceType
              AND catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND resource_key = :resourceKey
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("resourceType", address.type.wireName)
            .bindCatalog("catalogKey", testCatalog(tenantKey, address.catalogKey))
            .bind("resourceKey", address.key)
            .map { rs, _ -> ResourceIdentity.of(rs.getString("resource_id")) }
            .findOne()
            .orElse(null)
    }

    private fun templateEmbedding(stencilKey: String, catalogKey: String? = null): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("children")),
            "stencil-instance" to Node(
                id = "stencil-instance",
                type = "stencil",
                props = buildMap {
                    put("stencilKey", stencilKey)
                    catalogKey?.let { put("catalogKey", it) }
                },
            ),
        ),
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = listOf("stencil-instance"))),
        themeRef = ThemeRef.Inherit,
    )

    private fun emptyTemplate(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
        themeRef = ThemeRef.Inherit,
    )

    private fun unzipText(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) put(entry.name, String(zip.readAllBytes(), StandardCharsets.UTF_8))
                entry = zip.nextEntry
            }
        }
    }
}
