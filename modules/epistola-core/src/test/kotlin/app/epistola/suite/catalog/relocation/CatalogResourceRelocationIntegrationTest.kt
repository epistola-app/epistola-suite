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
import app.epistola.suite.stencils.queries.GetStencilUsagePage
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.versions.CreateVersion
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
import java.util.UUID
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
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }

        assertThat(preview.executable).isTrue()
        assertThat(preview.mutableRewriteCount).isEqualTo(1)
        assertThat(preview.immutableReferenceCount).isEqualTo(1)

        withMediator {
            MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), preview.planFingerprint).execute()
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
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }

        withMediator { UpdateDraft(variantId, emptyTemplate()).execute() }

        assertThatThrownBy {
            withMediator {
                MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), preview.planFingerprint).execute()
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
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), preview.planFingerprint).execute() }

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
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(movedAddress.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(movedAddress.movedTo(targetCatalog)), preview.planFingerprint).execute() }

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

        val out = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), out.planFingerprint).execute() }

        val back = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(movedAddress.movedTo(sourceCatalog))).query() }
        assertThat(back.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, listOf(movedAddress.movedTo(sourceCatalog)), back.planFingerprint).execute() }

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

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(sourceAddress.movedTo(targetCatalog))).query() }
        assertThat(preview.mutableRewriteCount).isEqualTo(1)

        // A draft that has nothing to do with the moving stencil changes in the meantime.
        withMediator { UpdateDraft(unrelatedVariant, templateEmbedding("unrelated-stencil")).execute() }

        withMediator { MoveCatalogResources(tenant.id, listOf(sourceAddress.movedTo(targetCatalog)), preview.planFingerprint).execute() }

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

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(headerAddress.movedTo(targetCatalog))).query() }

        assertThat(preview.blockers).noneSatisfy { assertThat(it.code).isEqualTo("immutable-relative-reference") }
        assertThat(preview.executable).isTrue()

        withMediator { MoveCatalogResources(tenant.id, listOf(headerAddress.movedTo(targetCatalog)), preview.planFingerprint).execute() }

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

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }

        // Every reference to an attribute is a mutable variant key, so none survives on an alias.
        assertThat(preview.blockers).isEmpty()
        assertThat(preview.mutableRewriteCount).isEqualTo(1)
        assertThat(preview.immutableReferenceCount).isZero()

        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

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
            PreviewCatalogResourceMove(tenant.id, listOf(ResourceAddress(CatalogResourceType.THEME, sourceCatalog.value, "brand").movedTo(targetCatalog))).query()
        }

        assertThat(preview.blockers).anySatisfy { assertThat(it.code).isEqualTo("unsupported-resource-type") }
        assertThat(preview.executable).isFalse()
    }

    @Test
    fun `moves a template with its hierarchy while generation history keeps its original address`() {
        val tenant = createTenant("Move template")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val templateId = TemplateId(TemplateKey.of("invoice"), CatalogId(sourceCatalog, tenantId))
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val address = ResourceAddress(CatalogResourceType.TEMPLATE, sourceCatalog.value, templateId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variantId, emptyTemplate()).execute()
            PublishVersion(VersionId(GetDraft(variantId).query()!!.id, variantId)).execute()
        }

        // A generation record predating the move, planted directly: it must survive unchanged, and
        // no command produces one against an arbitrary historical address.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO documents (id, tenant_key, catalog_key, template_key, variant_key, version_key,
                                       filename, size_bytes, created_at)
                VALUES (gen_random_uuid(), :tenantKey, :catalogKey, :templateKey, :variantKey, 1,
                        'invoice.pdf', 1024, NOW())
                """,
            )
                .bind("tenantKey", tenant.id)
                .bind("catalogKey", sourceCatalog)
                .bind("templateKey", templateId.key)
                .bind("variantKey", variantId.key)
                .execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        assertThat(preview.blockers).isEmpty()

        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // The template and its owned hierarchy followed.
        assertThat(withMediator { ResolveCatalogResourceAddress(tenant.id, address).query()!! }.canonical.catalogKey)
            .isEqualTo(targetCatalog.value)
        assertThat(catalogKeysIn("template_variants", tenant.id)).containsExactly(targetCatalog.value)
        assertThat(catalogKeysIn("template_versions", tenant.id)).containsExactly(targetCatalog.value)

        // Generation history did not: it records where the template lived at the time.
        assertThat(catalogKeysIn("documents", tenant.id)).containsExactly(sourceCatalog.value)

        // The link back to the template is by identity, so it survives the move that the address
        // deliberately does not follow.
        val templateIdentity = withMediator {
            ResolveCatalogResourceAddress(
                tenant.id,
                ResourceAddress(CatalogResourceType.TEMPLATE, targetCatalog.value, templateId.key.value),
            ).query()!!.resourceId
        }
        assertThat(documentTemplateIdentities(tenant.id)).containsExactly(templateIdentity)
    }

    private fun documentTemplateIdentities(tenantKey: TenantKey): List<UUID> = jdbi.withHandle<List<UUID>, Exception> { handle ->
        handle.createQuery("SELECT DISTINCT template_resource_id FROM documents WHERE tenant_key = :tenantKey")
            .bind("tenantKey", tenantKey)
            .mapTo(UUID::class.java)
            .list()
    }

    private fun catalogKeysIn(table: String, tenantKey: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT DISTINCT catalog_key::text FROM $table WHERE tenant_key = :tenantKey")
            .bind("tenantKey", tenantKey)
            .mapTo(String::class.java)
            .list()
    }

    @Test
    fun `a template reopened after a move republishes against the new address`() {
        val tenant = createTenant("Reopen after move")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = CatalogId(sourceCatalog, tenantId)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val address = ResourceAddress(CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), stencilId)).execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variantId, templateEmbedding(stencilId.key.value)).execute()
            PublishVersion(VersionId(GetDraft(variantId).query()!!.id, variantId)).execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // Reopening copies the published model, which still names the vacated address. The copy is
        // mutable, so it is canonicalised; the published version itself keeps its original bytes.
        val draft = withMediator {
            CreateVersion(variantId).execute()
            GetDraft(variantId).query()!!
        }
        assertThat(draft.templateModel.nodes.getValue("stencil-instance").props?.get("catalogKey"))
            .isEqualTo(targetCatalog.value)

        // Without that, publish validation looks for the stencil at an address nothing occupies and
        // the template becomes permanently unpublishable.
        withMediator { PublishVersion(VersionId(draft.id, variantId)).execute() }
    }

    @Test
    fun `usage still reports templates that reference a moved stencil by its old address`() {
        val tenant = createTenant("Usage after move")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val sourceCatalogId = CatalogId(sourceCatalog, tenantId)
        val stencilId = StencilId(StencilKey.of("header"), sourceCatalogId)
        val templateId = TemplateId(TemplateKey.of("invoice"), sourceCatalogId)
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        val address = ResourceAddress(CatalogResourceType.STENCIL, sourceCatalog.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), stencilId)).execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variantId, templateEmbedding(stencilId.key.value)).execute()
            PublishVersion(VersionId(GetDraft(variantId).query()!!.id, variantId)).execute()
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // The published version keeps naming letters/header. Asking the moved stencil what uses it
        // must still surface that template, or a delete-with-force would look safe when the alias is
        // the only thing keeping those references resolvable.
        val movedStencil = StencilId(stencilId.key, CatalogId(targetCatalog, tenantId))
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
        val lettersId = CatalogId(letters, tenantId)
        val sharedId = CatalogId(shared, tenantId)

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
            UpdateStencilDraft(StencilVersionId(VersionKey.of(1), header), usesTheme(lettersTheme.key.value, letters.value)).execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
            // And a template in letters depends on the shared theme, so letters -> shared exists.
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variantId, usesTheme(sharedTheme.key.value, shared.value)).execute()
        }

        // Moving the stencil into shared would make shared depend on letters, closing the loop.
        val preview = withMediator {
            PreviewCatalogResourceMove(tenant.id, listOf(ResourceAddress(CatalogResourceType.STENCIL, letters.value, header.key.value).movedTo(shared))).query()
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
        themeRef = ThemeRefOverride(themeId = themeKey, catalogKey = catalogKey),
    )

    @Test
    fun `a relocation can rename as well as move`() {
        val tenant = createTenant("Rename on move")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")
        val stencilId = StencilId(StencilKey.of("header"), CatalogId(letters, tenantId))
        val address = ResourceAddress(CatalogResourceType.STENCIL, letters.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), stencilId)).execute()
        }

        val relocation = address.movedTo(shared, "letterhead")
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(relocation)).query() }
        assertThat(preview.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, listOf(relocation), preview.planFingerprint).execute() }

        // Both halves of the address changed, and the old one still resolves.
        val resolved = withMediator { ResolveCatalogResourceAddress(tenant.id, address).query()!! }
        assertThat(resolved.canonical.catalogKey).isEqualTo(shared.value)
        assertThat(resolved.canonical.key).isEqualTo("letterhead")
        assertThat(resolved.resolvedViaAlias).isTrue()

        // The owned hierarchy followed the rename, not only the catalog change: the ON UPDATE
        // CASCADE foreign key fires on any referenced column.
        val renamed = StencilId(StencilKey.of("letterhead"), CatalogId(shared, tenantId))
        assertThat(withMediator { ListStencilVersions(renamed).query() }).hasSize(1)
    }

    @Test
    fun `a member can take an address another member is vacating`() {
        val tenant = createTenant("Handover batch")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")
        val header = StencilId(StencilKey.of("header"), CatalogId(letters, tenantId))
        val draft = StencilId(StencilKey.of("draft-header"), CatalogId(letters, tenantId))
        val headerAddress = ResourceAddress(CatalogResourceType.STENCIL, letters.value, "header")
        val draftAddress = ResourceAddress(CatalogResourceType.STENCIL, letters.value, "draft-header")

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            CreateStencil(header, "Header").execute()
            CreateStencil(draft, "Draft header").execute()
        }

        // Promote the draft into the name the old one is vacating. Neither move works alone: the
        // rename is blocked while the original still holds the address.
        val batch = listOf(headerAddress.movedTo(shared), draftAddress.renamedTo("header"))
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, batch).query() }

        assertThat(preview.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, batch, preview.planFingerprint).execute() }

        // letters/header is now the promoted resource, not the one that left.
        val promoted = withMediator { ResolveCatalogResourceAddress(tenant.id, draftAddress).query()!! }
        assertThat(promoted.canonical).isEqualTo(headerAddress)
        val moved = withMediator { ResolveCatalogResourceAddress(tenant.id, headerAddress).query()!! }
        assertThat(moved.resourceId).isEqualTo(promoted.resourceId)
    }

    @Test
    fun `two resources exchanging addresses is refused rather than half applied`() {
        val tenant = createTenant("Swap refused")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val header = StencilId(StencilKey.of("header"), CatalogId(letters, tenantId))
        val footer = StencilId(StencilKey.of("footer"), CatalogId(letters, tenantId))
        val headerAddress = ResourceAddress(CatalogResourceType.STENCIL, letters.value, "header")
        val footerAddress = ResourceAddress(CatalogResourceType.STENCIL, letters.value, "footer")

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
        val good = StencilId(StencilKey.of("header"), CatalogId(letters, tenantId))
        val goodAddress = ResourceAddress(CatalogResourceType.STENCIL, letters.value, "header")
        val missingAddress = ResourceAddress(CatalogResourceType.STENCIL, letters.value, "does-not-exist")

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
        assertThat(withMediator { ResolveCatalogResourceAddress(tenant.id, goodAddress).query()!! }.canonical)
            .isEqualTo(goodAddress)
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
