// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.GetTenantResourceGraph
import app.epistola.suite.catalog.graph.ReferenceSelector
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.identity.ResolveCatalogResourceAddress
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class CatalogResourceRelocationIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

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
    fun `an alias shadowed by a new resource at the old address does not rewrite exports`() {
        val tenant = createTenant("Shadowed alias export")
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
        }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, sourceAddress, targetCatalog).query() }
        withMediator { MoveCatalogResource(tenant.id, sourceAddress, targetCatalog, preview.planFingerprint).execute() }

        // A brand new, unrelated stencil now legitimately occupies the vacated address.
        withMediator {
            CreateStencil(stencilId, "Replacement header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), stencilId)).execute()
            CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variantId, templateEmbedding(stencilId.key.value, sourceCatalog.value)).execute()
            PublishVersion(VersionId(GetDraft(variantId).query()!!.id, variantId)).execute()
        }

        val exportEntries = withMediator { ExportCatalogZip(tenant.id, sourceCatalog).execute().zipBytes }
            .let(::unzipText)

        // The replacement stencil is exported from its own catalog, and the template's explicit
        // reference to it must survive verbatim instead of being redirected by the stale alias.
        assertThat(exportEntries).containsKey("resources/stencil/header.json")
        assertThat(exportEntries.getValue("resources/template/invoice.json"))
            .contains("\"catalogKey\":\"letters\"")
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
