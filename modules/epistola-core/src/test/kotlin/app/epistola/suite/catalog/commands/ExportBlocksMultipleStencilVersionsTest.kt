// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.MultipleStencilVersionsInUseException
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExportBlocksMultipleStencilVersionsTest : IntegrationTestBase() {

    @Test
    fun `export fails when published templates pin same own-catalog stencil at different versions`() {
        val tenant = createTenant("Multi-Version Block")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("multi-ver-block")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Multi-Version Block").execute()

            // Stencil with two published versions (v1 then v2)
            val stencilKey = StencilKey.of("shared-stencil")
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Shared Stencil", content = stencilContent("v1-root")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
            CreateStencilVersion(stencilId = stencilId, content = stencilContent("v2-root")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), stencilId)).execute()

            // Template A pinned to stencil v1
            publishTemplateReferencingStencil(catalogId, "template-a", stencilKey, stencilVersion = 1)

            // Template B pinned to stencil v2
            publishTemplateReferencingStencil(catalogId, "template-b", stencilKey, stencilVersion = 2)

            assertThatThrownBy {
                ExportCatalogZip(tenantKey = tenantKey, catalogKey = catalogKey).execute()
            }
                .isInstanceOf(MultipleStencilVersionsInUseException::class.java)
                .satisfies({ ex ->
                    val e = ex as MultipleStencilVersionsInUseException
                    assertThat(e.catalogKey).isEqualTo(catalogKey)
                    assertThat(e.stencils).hasSize(1)
                    val conflict = e.stencils.single()
                    assertThat(conflict.stencilKey).isEqualTo(stencilKey)
                    assertThat(conflict.stencilName).isEqualTo("Shared Stencil")
                    assertThat(conflict.latestPublishedVersion).isEqualTo(2)
                    // Only the out-of-date template is named; template-b (already on
                    // the latest v2) is not listed.
                    assertThat(conflict.pins.map { it.templateName to it.pinnedVersion })
                        .containsExactly("template-a" to 1)
                })
        }
    }

    @Test
    fun `export fails when all templates pin a single but stale stencil version`() {
        // All templates agree on v1, but stencil's latest published is v2. The
        // export ships only the latest published, so a pin to v1 would not
        // resolve in target. Stricter check catches this case alongside
        // multi-version usage.
        val tenant = createTenant("Stale Pin Block")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("stale-pin-block")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Stale Pin Block").execute()

            val stencilKey = StencilKey.of("shared-stencil")
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Shared Stencil", content = stencilContent("v1-root")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
            CreateStencilVersion(stencilId = stencilId, content = stencilContent("v2-root")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), stencilId)).execute()

            // Both templates pin v1 — consistent but stale (latest is v2)
            publishTemplateReferencingStencil(catalogId, "template-a", stencilKey, stencilVersion = 1)
            publishTemplateReferencingStencil(catalogId, "template-b", stencilKey, stencilVersion = 1)

            assertThatThrownBy {
                ExportCatalogZip(tenantKey = tenantKey, catalogKey = catalogKey).execute()
            }
                .isInstanceOf(MultipleStencilVersionsInUseException::class.java)
                .satisfies({ ex ->
                    val e = ex as MultipleStencilVersionsInUseException
                    val conflict = e.stencils.single()
                    // Both templates pin the stale v1, so both are named.
                    assertThat(conflict.pins.map { it.templateName to it.pinnedVersion })
                        .containsExactlyInAnyOrder("template-a" to 1, "template-b" to 1)
                    assertThat(conflict.latestPublishedVersion).isEqualTo(2)
                })
        }
    }

    @Test
    fun `export succeeds when all templates pin the same stencil version`() {
        val tenant = createTenant("Single-Version OK")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("single-ver-ok")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Single-Version OK").execute()

            val stencilKey = StencilKey.of("shared-stencil")
            val stencilId = StencilId(stencilKey, catalogId)
            CreateStencil(id = stencilId, name = "Shared", content = stencilContent("v1-root")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()

            publishTemplateReferencingStencil(catalogId, "template-a", stencilKey, stencilVersion = 1)
            publishTemplateReferencingStencil(catalogId, "template-b", stencilKey, stencilVersion = 1)

            val result = ExportCatalogZip(tenantKey = tenantKey, catalogKey = catalogKey).execute()
            assertThat(result.zipBytes).isNotEmpty()
        }
    }

    private fun publishTemplateReferencingStencil(
        catalogId: CatalogId,
        templateSlug: String,
        stencilKey: StencilKey,
        stencilVersion: Int,
    ) {
        val templateKey = TemplateKey.of(templateSlug)
        val templateId = TemplateId(templateKey, catalogId)
        CreateDocumentTemplate(id = templateId, name = templateSlug).execute()

        val variantKey = VariantKey.INITIAL
        val variantId = VariantId(variantKey, templateId)
        UpdateDraft(
            variantId = variantId,
            templateModel = templateEmbeddingStencil(stencilKey, stencilVersion),
        ).execute()
        PublishVersion(versionId = VersionId(VersionKey.of(1), variantId)).execute()
    }

    private fun stencilContent(rootId: String): TemplateDocument {
        val slotId = "slot-$rootId"
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(
                rootId to Node(id = rootId, type = "root", slots = listOf(slotId)),
            ),
            slots = mapOf(
                slotId to Slot(id = slotId, nodeId = rootId, name = "children", children = emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )
    }

    private fun templateEmbeddingStencil(stencilKey: StencilKey, version: Int): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-instance" to Node(
                id = "stencil-instance",
                type = "stencil",
                slots = listOf("stencil-children"),
                props = mapOf("stencilId" to stencilKey.value, "version" to version),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("stencil-instance")),
            "stencil-children" to Slot(id = "stencil-children", nodeId = "stencil-instance", name = "children", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
