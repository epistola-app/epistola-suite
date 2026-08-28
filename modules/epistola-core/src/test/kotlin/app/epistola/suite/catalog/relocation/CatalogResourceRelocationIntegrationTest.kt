// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.GetTenantResourceGraph
import app.epistola.suite.catalog.graph.ReferenceSelector
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.identity.CatalogResourceAddressReservedException
import app.epistola.suite.catalog.identity.PreviewCatalogResourceAliasRelease
import app.epistola.suite.catalog.identity.ReleaseCatalogResourceAlias
import app.epistola.suite.catalog.identity.ResolveCatalogResourceAddress
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
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
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencilDraft
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
    fun `moves stencil atomically while preserving published references through alias`() {
        val tenant = createTenant("Move stencil")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = CatalogId(sourceCatalog, tenantId)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val sourceAddress = ResourceAddress(CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), stencilId)).execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variantId, templateEmbedding(stencilId.key.value)).execute()
            val publishedDraft = GetDraft(variantId).query()!!
            PublishVersion(VersionId(publishedDraft.id, variantId)).execute()
            UpdateDraft(variantId, templateEmbedding(stencilId.key.value)).execute()
        }

        val before = withMediator { ResolveCatalogResourceAddress(tenant.id, sourceAddress).query()!! }
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, sourceAddress, targetCatalog).query() }

        assertThat(preview.executable).isTrue()
        assertThat(preview.mutableRewriteCount).isEqualTo(1)
        assertThat(preview.immutableReferenceCount).isEqualTo(1)

        withMediator {
            MoveCatalogResource(tenant.id, sourceAddress, targetCatalog, preview.planFingerprint).execute()
        }

        val oldResolution = withMediator { ResolveCatalogResourceAddress(tenant.id, sourceAddress).query()!! }
        assertThat(oldResolution.resourceId).isEqualTo(before.resourceId)
        assertThat(oldResolution.canonical.catalogKey).isEqualTo(targetCatalog.value)
        assertThat(oldResolution.resolvedViaAlias).isTrue()

        val movedStencilId = StencilId(stencilId.key, CatalogId(targetCatalog, tenantId))
        assertThat(withMediator { ListStencilVersions(movedStencilId).query() }).hasSize(1)

        val draft = withMediator { GetDraft(variantId).query()!! }
        assertThat(draft.templateModel.nodes.getValue("stencil-instance").props?.get("catalogKey"))
            .isEqualTo(targetCatalog.value)

        val graph = withMediator { GetTenantResourceGraph(tenant.id, includeHistory = true).query() }
        assertThat(graph.edges)
            .filteredOn { it.targetSelector == ReferenceSelector(CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value) }
            .anySatisfy { edge ->
                assertThat(edge.target?.catalogKey).isEqualTo(targetCatalog.value)
                assertThat(edge.resolvedViaAlias).isTrue()
            }

        val exportEntries = withMediator { ExportCatalogZip(tenant.id, sourceCatalog).execute().zipBytes }
            .let(::unzipText)
        assertThat(exportEntries).doesNotContainKey("resources/stencil/header.json")
        assertThat(exportEntries.getValue("resources/template/invoice.json"))
            .contains("\"catalogKey\":\"shared\"")
            .contains("\"stencilId\":\"header\"")
        assertThat(exportEntries.getValue("catalog.json"))
            .contains("\"catalogKey\":\"shared\"")

        withMediator { DeleteStencil(movedStencilId, force = true).execute() }
        assertThat(withMediator { ResolveCatalogResourceAddress(tenant.id, sourceAddress).query() }).isNull()
    }

    @Test
    fun `execute rejects a stale preview after a draft changes`() {
        val tenant = createTenant("Stale stencil move")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = CatalogId(sourceCatalog, tenantId)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val sourceAddress = ResourceAddress(CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            CreateDocumentTemplate(templateId, "Invoice").execute()
            UpdateDraft(variantId, templateEmbedding(stencilId.key.value)).execute()
        }
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, sourceAddress, targetCatalog).query() }

        withMediator { UpdateDraft(variantId, emptyTemplate()).execute() }

        assertThatThrownBy {
            withMediator {
                MoveCatalogResource(tenant.id, sourceAddress, targetCatalog, preview.planFingerprint).execute()
            }
        }.isInstanceOf(StaleCatalogResourceMovePlanException::class.java)

        assertThat(withMediator { ResolveCatalogResourceAddress(tenant.id, sourceAddress).query()!!.resolvedViaAlias }).isFalse()
    }

    @Test
    fun `the address a relocated resource left behind is reserved until released`() {
        val tenant = createTenant("Reserved address")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val stencilId = StencilId(StencilKey.of("header"), CatalogId(sourceCatalog, tenantId))
        val sourceAddress = ResourceAddress(CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
        }
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, sourceAddress, targetCatalog).query() }
        withMediator { MoveCatalogResource(tenant.id, sourceAddress, targetCatalog, preview.planFingerprint).execute() }

        // Reusing the vacated address would make every published reference to it ambiguous.
        assertThatThrownBy { withMediator { CreateStencil(stencilId, "Replacement").execute() } }
            .isInstanceOf(CatalogResourceAddressReservedException::class.java)

        val impact = withMediator { PreviewCatalogResourceAliasRelease(tenant.id, sourceAddress).query()!! }
        assertThat(impact.canonical?.catalogKey).isEqualTo(targetCatalog.value)

        // Releasing is deliberate and gives the address back.
        withMediator { ReleaseCatalogResourceAlias(tenant.id, sourceAddress).execute() }
        withMediator { CreateStencil(stencilId, "Replacement").execute() }

        val reused = withMediator { ResolveCatalogResourceAddress(tenant.id, sourceAddress).query()!! }
        assertThat(reused.canonical.catalogKey).isEqualTo(sourceCatalog.value)
        assertThat(reused.resolvedViaAlias).isFalse()
    }

    @Test
    fun `a canonical resource shadowing an imported alias does not rewrite exports`() {
        val tenant = createTenant("Shadowed alias export")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = CatalogId(sourceCatalog, tenantId)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val movedId = StencilId(StencilKey.of("moved"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val movedAddress = ResourceAddress(CatalogResourceType.STENCIL, sourceCatalog.value, movedId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(movedId, "Moved").execute()
            CreateStencil(stencilId, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), stencilId)).execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variantId, templateEmbedding(stencilId.key.value, sourceCatalog.value)).execute()
            PublishVersion(VersionId(GetDraft(variantId).query()!!.id, variantId)).execute()
        }
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, movedAddress, targetCatalog).query() }
        withMediator { MoveCatalogResource(tenant.id, movedAddress, targetCatalog, preview.planFingerprint).execute() }

        // Authoring reserves an aliased address, so only import or backup restore can produce an
        // alias shadowed by a canonical resource. Planted directly for that reason.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_resource_aliases SET resource_key = :shadowed
                WHERE tenant_key = :tenantKey AND resource_key = :moved
                """,
            )
                .bind("tenantKey", tenant.id)
                .bind("shadowed", stencilId.key.value)
                .bind("moved", movedId.key.value)
                .execute()
        }

        val exportEntries = withMediator { ExportCatalogZip(tenant.id, sourceCatalog).execute().zipBytes }
            .let(::unzipText)

        assertThat(exportEntries).containsKey("resources/stencil/header.json")
        // Same-catalog references travel relative, so the surviving evidence that the stale alias
        // was ignored is that the reference was not redirected into the destination catalog.
        assertThat(exportEntries.getValue("resources/template/invoice.json"))
            .contains("\"stencilId\":\"header\"")
            .doesNotContain("\"catalogKey\":\"shared\"")
    }

    @Test
    fun `a resource can be moved back to a catalog it previously occupied`() {
        val tenant = createTenant("Move stencil back")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val stencilId = StencilId(StencilKey.of("header"), CatalogId(sourceCatalog, tenantId))
        val sourceAddress = ResourceAddress(CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)
        val movedAddress = ResourceAddress(CatalogResourceType.STENCIL, targetCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
        }

        val out = withMediator { PreviewCatalogResourceMove(tenant.id, sourceAddress, targetCatalog).query() }
        withMediator { MoveCatalogResource(tenant.id, sourceAddress, targetCatalog, out.planFingerprint).execute() }

        val back = withMediator { PreviewCatalogResourceMove(tenant.id, movedAddress, sourceCatalog).query() }
        assertThat(back.blockers).isEmpty()
        withMediator { MoveCatalogResource(tenant.id, movedAddress, sourceCatalog, back.planFingerprint).execute() }

        val home = withMediator { ResolveCatalogResourceAddress(tenant.id, sourceAddress).query()!! }
        assertThat(home.canonical.catalogKey).isEqualTo(sourceCatalog.value)
        assertThat(home.resolvedViaAlias).isFalse()

        // References captured while it lived in the other catalog still resolve.
        val away = withMediator { ResolveCatalogResourceAddress(tenant.id, movedAddress).query()!! }
        assertThat(away.canonical.catalogKey).isEqualTo(sourceCatalog.value)
        assertThat(away.resolvedViaAlias).isTrue()

        // The reclaimed address must not keep a redundant alias row behind.
        assertThat(aliasCatalogKeys(tenant.id)).containsExactly(targetCatalog.value)
    }

    @Test
    fun `an unrelated edit elsewhere in the tenant does not invalidate the plan`() {
        val tenant = createTenant("Unrelated edit")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = CatalogId(sourceCatalog, tenantId)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val referencingVariant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), sourceCatalogId))
        val unrelatedVariant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("memo"), sourceCatalogId))
        val sourceAddress = ResourceAddress(CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), sourceCatalogId), "Invoice").execute()
            UpdateDraft(referencingVariant, templateEmbedding(stencilId.key.value)).execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("memo"), sourceCatalogId), "Memo").execute()
            UpdateDraft(unrelatedVariant, emptyTemplate()).execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, sourceAddress, targetCatalog).query() }
        assertThat(preview.mutableRewriteCount).isEqualTo(1)

        // A draft that has nothing to do with the moving stencil changes in the meantime.
        withMediator { UpdateDraft(unrelatedVariant, templateEmbedding("unrelated-stencil")).execute() }

        withMediator { MoveCatalogResource(tenant.id, sourceAddress, targetCatalog, preview.planFingerprint).execute() }

        assertThat(withMediator { ResolveCatalogResourceAddress(tenant.id, sourceAddress).query()!! }.resolvedViaAlias).isTrue()
    }

    @Test
    fun `write-time qualification lets a stencil with published dependencies move`() {
        val tenant = createTenant("Qualified on write")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = CatalogId(sourceCatalog, tenantId)
        val header = StencilId(StencilKey.of("header"), sourceCatalogId)
        val headerAddress = ResourceAddress(CatalogResourceType.STENCIL, sourceCatalog.value, header.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), sourceCatalogId), "Brand").execute()
            CreateStencil(header, "Header").execute()
            // Authored WITHOUT a catalogKey: before write-time qualification this stored a relative
            // reference, which a move could not preserve once published.
            UpdateStencilDraft(StencilVersionId(VersionKey.of(1), header), dependsOnThemeRelatively()).execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, headerAddress, targetCatalog).query() }

        assertThat(preview.blockers).noneSatisfy { assertThat(it.code).isEqualTo("immutable-relative-reference") }
        assertThat(preview.executable).isTrue()

        withMediator { MoveCatalogResource(tenant.id, headerAddress, targetCatalog, preview.planFingerprint).execute() }

        // The published dependency still names the catalog the theme actually lives in.
        val graph = withMediator { GetTenantResourceGraph(tenant.id, includeHistory = true).query() }
        assertThat(graph.edges)
            .filteredOn { it.targetSelector.type == CatalogResourceType.THEME }
            .allSatisfy { assertThat(it.target?.catalogKey).isEqualTo(sourceCatalog.value) }
    }

    private fun dependsOnThemeRelatively(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
        themeRef = ThemeRefOverride(themeId = "brand"),
    )

    @Test
    fun `moves an attribute and repoints the variants that name it`() {
        val tenant = createTenant("Move attribute")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = CatalogId(sourceCatalog, tenantId)
        val attribute = AttributeId(AttributeKey.of("brand"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val address = ResourceAddress(CatalogResourceType.ATTRIBUTE, sourceCatalog.value, attribute.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateAttributeDefinition(attribute, "Brand", allowedValues = listOf("acme")).execute()
            CreateDocumentTemplate(templateId, "Invoice").execute()
            UpdateVariant(variantId, "Main", mapOf("letters.brand" to "acme")).execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, address, targetCatalog).query() }

        // Every reference to an attribute is a mutable variant key, so none survives on an alias.
        assertThat(preview.blockers).isEmpty()
        assertThat(preview.mutableRewriteCount).isEqualTo(1)
        assertThat(preview.immutableReferenceCount).isZero()

        withMediator { MoveCatalogResource(tenant.id, address, targetCatalog, preview.planFingerprint).execute() }

        val resolved = withMediator { ResolveCatalogResourceAddress(tenant.id, address).query()!! }
        assertThat(resolved.canonical.catalogKey).isEqualTo(targetCatalog.value)

        // The variant now names the attribute at its new address, with its value intact.
        assertThat(variantAttributes(tenant.id, templateId, variantId))
            .containsExactlyEntriesOf(mapOf("shared.brand" to "acme"))
    }

    @Test
    fun `a resource type that is still keyed by address cannot move`() {
        val tenant = createTenant("Unsupported move")
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(sourceCatalog, TenantId(tenant.id))), "Brand").execute()
        }

        val preview = withMediator {
            PreviewCatalogResourceMove(
                tenant.id,
                ResourceAddress(CatalogResourceType.THEME, sourceCatalog.value, "brand"),
                targetCatalog,
            ).query()
        }

        assertThat(preview.blockers).anySatisfy { assertThat(it.code).isEqualTo("unsupported-resource-type") }
        assertThat(preview.executable).isFalse()
    }

    private fun variantAttributes(tenantKey: TenantKey, templateId: TemplateId, variantId: VariantId): Map<String, String> = jdbi.withHandle<Map<String, String>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT attributes::text FROM template_variants
                WHERE tenant_key = :tenantKey AND template_key = :templateKey AND id = :variantKey
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

    private fun aliasCatalogKeys(tenantKey: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT catalog_key::text FROM catalog_resource_aliases WHERE tenant_key = :tenantKey ORDER BY catalog_key")
            .bind("tenantKey", tenantKey)
            .mapTo(String::class.java)
            .list()
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
                    put("stencilId", stencilKey)
                    put("version", 1)
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
