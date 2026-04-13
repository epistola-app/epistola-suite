package app.epistola.suite.stencils

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.ArchiveStencilVersion
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.commands.UpdateStencilDraft
import app.epistola.suite.stencils.model.StencilVersionStatus
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilVersion
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.stencils.queries.ListStencils
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StencilIntegrationTest : IntegrationTestBase() {

    /** Wrapper that ensures withMediator returns Unit (JUnit requires void test methods). */
    private fun test(block: () -> Unit) = withMediator(block)

    private fun createTestContent(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
            "text1" to Node(id = "text1", type = "text", slots = emptyList(), props = mapOf("content" to "Hello")),
        ),
        slots = mapOf(
            "slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("text1")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    private fun stencilId(tenantId: TenantId) = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))

    // ── CRUD ──

    @Test
    fun `create and retrieve stencil`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = stencilId(tenantId)

        val stencil = CreateStencil(
            id = id,
            name = "Corporate Header",
            description = "Standard header",
            tags = listOf("header", "corporate"),
        ).execute()

        assertThat(stencil.name).isEqualTo("Corporate Header")
        assertThat(stencil.description).isEqualTo("Standard header")
        assertThat(stencil.tags).containsExactly("header", "corporate")

        val retrieved = GetStencil(id = id).query()
        assertThat(retrieved).isNotNull
        assertThat(retrieved!!.name).isEqualTo("Corporate Header")
    }

    @Test
    fun `create stencil with initial content creates draft version`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = stencilId(tenantId)

        CreateStencil(
            id = id,
            name = "With Content",
            content = createTestContent(),
        ).execute()

        val versions = ListStencilVersions(stencilId = id).query()
        assertThat(versions).hasSize(1)
        assertThat(versions[0].status).isEqualTo(StencilVersionStatus.DRAFT)
        assertThat(versions[0].id.value).isEqualTo(1)
    }

    @Test
    fun `list stencils with search`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        CreateStencil(id = stencilId(tenantId), name = "Corporate Header").execute()
        CreateStencil(id = stencilId(tenantId), name = "Invoice Footer").execute()

        val all = ListStencils(tenantId = tenantId).query()
        assertThat(all).hasSize(2)

        val searched = ListStencils(tenantId = tenantId, searchTerm = "Header").query()
        assertThat(searched).hasSize(1)
        assertThat(searched[0].name).isEqualTo("Corporate Header")
    }

    @Test
    fun `update stencil metadata`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = stencilId(tenantId)

        CreateStencil(id = id, name = "Old Name").execute()

        val updated = UpdateStencil(id = id, name = "New Name", tags = listOf("updated")).execute()
        assertThat(updated).isNotNull
        assertThat(updated!!.name).isEqualTo("New Name")
        assertThat(updated.tags).containsExactly("updated")
    }

    @Test
    fun `delete stencil cascades to versions`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = stencilId(tenantId)

        CreateStencil(id = id, name = "To Delete", content = createTestContent()).execute()
        assertThat(ListStencilVersions(stencilId = id).query()).hasSize(1)

        val deleted = DeleteStencil(id = id).execute()
        assertThat(deleted).isTrue()

        assertThat(GetStencil(id = id).query()).isNull()
    }

    // ── Version lifecycle ──

    @Test
    fun `version lifecycle - draft to published to archived`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = stencilId(tenantId)

        CreateStencil(id = id, name = "Lifecycle Test", content = createTestContent()).execute()
        val versionId = StencilVersionId(VersionKey.of(1), id)

        // Draft
        val draft = GetStencilVersion(versionId = versionId).query()
        assertThat(draft).isNotNull
        assertThat(draft!!.status).isEqualTo(StencilVersionStatus.DRAFT)
        assertThat(draft.publishedAt).isNull()

        // Publish
        val published = PublishStencilVersion(versionId = versionId).execute()
        assertThat(published).isNotNull
        assertThat(published!!.status).isEqualTo(StencilVersionStatus.PUBLISHED)
        assertThat(published.publishedAt).isNotNull()

        // Archive
        val archived = ArchiveStencilVersion(versionId = versionId).execute()
        assertThat(archived).isNotNull
        assertThat(archived!!.status).isEqualTo(StencilVersionStatus.ARCHIVED)
        assertThat(archived.archivedAt).isNotNull()
    }

    @Test
    fun `create version is idempotent - returns existing draft`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = stencilId(tenantId)

        CreateStencil(id = id, name = "Idempotent Test", content = createTestContent()).execute()

        val draft1 = CreateStencilVersion(stencilId = id).execute()
        val draft2 = CreateStencilVersion(stencilId = id).execute()

        assertThat(draft1!!.id).isEqualTo(draft2!!.id)
    }

    @Test
    fun `update draft content`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = stencilId(tenantId)

        CreateStencil(id = id, name = "Update Test", content = createTestContent()).execute()
        val versionId = StencilVersionId(VersionKey.of(1), id)

        val newContent = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
                "text-updated" to Node(id = "text-updated", type = "text", slots = emptyList(), props = mapOf("content" to "Updated")),
            ),
            slots = mapOf(
                "slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("text-updated")),
            ),
            themeRef = ThemeRef.Inherit,
        )

        val updated = UpdateStencilDraft(versionId = versionId, content = newContent).execute()
        assertThat(updated).isNotNull

        val retrieved = GetStencilVersion(versionId = versionId).query()
        assertThat(retrieved!!.content.nodes).containsKey("text-updated")
    }

    // ── Publish validation ──

    @Test
    fun `publish rejects stencil with nested stencil nodes`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = stencilId(tenantId)

        val contentWithNestedStencil = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
                "nested-stencil" to Node(
                    id = "nested-stencil",
                    type = "stencil",
                    slots = emptyList(),
                    props = mapOf("stencilId" to "other", "version" to 1),
                ),
            ),
            slots = mapOf(
                "slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("nested-stencil")),
            ),
            themeRef = ThemeRef.Inherit,
        )

        CreateStencil(id = id, name = "Nested Test", content = contentWithNestedStencil).execute()
        val versionId = StencilVersionId(VersionKey.of(1), id)

        assertThatThrownBy {
            PublishStencilVersion(versionId = versionId).execute()
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("stencil")
    }

    // ── Tenant isolation ──

    @Test
    fun `tenant isolation - cannot see other tenant stencils`() = test {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")
        val tenantId1 = TenantId(tenant1.id)
        val tenantId2 = TenantId(tenant2.id)

        CreateStencil(id = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId1)), name = "T1 Stencil").execute()
        CreateStencil(id = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId2)), name = "T2 Stencil").execute()

        val t1Stencils = ListStencils(tenantId = tenantId1).query()
        val t2Stencils = ListStencils(tenantId = tenantId2).query()

        assertThat(t1Stencils).hasSize(1)
        assertThat(t1Stencils[0].name).isEqualTo("T1 Stencil")
        assertThat(t2Stencils).hasSize(1)
        assertThat(t2Stencils[0].name).isEqualTo("T2 Stencil")
    }

    // ── Upgrade stencil in template ──

    @Test
    fun `upgrade stencil in template - no draft returns null`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val stencilId = stencilId(tenantId)

        CreateStencil(id = stencilId, name = "Header", content = createTestContent()).execute()

        // Use a non-existent template
        val fakeTemplateId = TemplateId(TemplateKey.of("non-existent"), CatalogId.default(tenantId))
        val fakeVariantId = VariantId(VariantKey.of("non-existent"), fakeTemplateId)

        val result = app.epistola.suite.stencils.commands.UpdateStencilInTemplate(
            variantId = fakeVariantId,
            stencilId = stencilId,
            newVersion = 1,
        ).execute()

        assertThat(result).isNull()
    }

    @Test
    fun `upgrade stencil in template - no stencil instances returns zero`() = test {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val stencilId = stencilId(tenantId)

        CreateStencil(id = stencilId, name = "Header", content = createTestContent()).execute()

        // Create a template WITHOUT stencil nodes
        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(
            id = templateId,
            name = "Test Template",
        ).execute()

        val variantKey = VariantKey.of("${templateKey.value}-default")
        val variantId = VariantId(variantKey, templateId)

        val count = app.epistola.suite.stencils.commands.UpdateStencilInTemplate(
            variantId = variantId,
            stencilId = stencilId,
            newVersion = 1,
        ).execute()

        assertThat(count).isEqualTo(0)
    }
}
