// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class VersionScopingTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `update version scopes by template key when variant and version ids collide`(): Unit = withMediator {
        val (_, firstVariantId, secondVariantId) = createTemplatesWithSharedVariant()
        val firstVersionId = VersionId(VersionKey.of(1), firstVariantId)
        val secondVersionId = VersionId(VersionKey.of(1), secondVariantId)

        UpdateDraft(secondVariantId, templateDocument("original-second")).execute()

        val updated = UpdateVersion(firstVersionId, templateDocument("updated-first")).execute()
        val second = GetVersion(secondVersionId).query()

        assertThat(updated.templateModel.root).isEqualTo("root-updated-first")
        assertThat(second!!.templateModel.root).isEqualTo("root-original-second")
    }

    @Test
    fun `archive version ignores active matching version in another template`(): Unit = withMediator {
        val (tenantId, firstVariantId, secondVariantId) = createTemplatesWithSharedVariant()
        val firstVersionId = VersionId(VersionKey.of(1), firstVariantId)
        val secondVersionId = VersionId(VersionKey.of(1), secondVariantId)
        val environmentId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        CreateEnvironment(id = environmentId, name = "Staging").execute()

        PublishContractVersion(firstVariantId.templateId).execute()
        PublishContractVersion(secondVariantId.templateId).execute()
        PublishVersion(firstVersionId).execute()
        PublishToEnvironment(secondVersionId, environmentId).execute()

        val archived = ArchiveVersion(firstVersionId).execute()
        val stillPublished = GetVersion(secondVersionId).query()

        assertThat(archived.status).isEqualTo(VersionStatus.ARCHIVED)
        assertThat(stillPublished!!.status).isEqualTo(VersionStatus.PUBLISHED)
    }

    @Test
    fun `update version synchronizes referenced paths with the stored model`(): Unit = withMediator {
        val tenant = createTenant("Version Paths Test")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        CreateDocumentTemplate(id = templateId, name = "Referenced Paths").execute()

        UpdateVersion(
            VersionId(VersionKey.of(1), variantId),
            templateDocumentWithPath("customer.name"),
        ).execute()

        val referencedPaths = jdbi.withHandle<String, Exception> { handle ->
            handle.createQuery(
                """
                SELECT referenced_paths::text
                FROM template_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND variant_key = :variantKey AND id = 1
                """,
            )
                .bind("tenantKey", templateId.tenantKey)
                .bind("catalogKey", templateId.catalogKey)
                .bind("templateKey", templateId.key)
                .bind("variantKey", variantId.key)
                .mapTo(String::class.java)
                .one()
        }

        assertThat(referencedPaths).isEqualTo("""["customer.name"]""")
    }

    private fun createTemplatesWithSharedVariant(): Triple<TenantId, VariantId, VariantId> {
        val tenant = createTenant("Version Scoping Test")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId(CatalogKey.DEFAULT, tenantId)
        val sharedVariantKey = TestIdHelpers.nextVariantId()
        val firstTemplateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        val secondTemplateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        val firstVariantId = VariantId(sharedVariantKey, firstTemplateId)
        val secondVariantId = VariantId(sharedVariantKey, secondTemplateId)

        CreateDocumentTemplate(id = firstTemplateId, name = "First Template").execute()
        CreateDocumentTemplate(id = secondTemplateId, name = "Second Template").execute()
        CreateVariant(id = firstVariantId, title = "Shared", description = null).execute()
        CreateVariant(id = secondVariantId, title = "Shared", description = null).execute()

        return Triple(tenantId, firstVariantId, secondVariantId)
    }

    private fun templateDocument(suffix: String): TemplateDocument {
        val rootId = "root-$suffix"
        val slotId = "slot-$suffix"
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(rootId to Node(id = rootId, type = "root", slots = listOf(slotId))),
            slots = mapOf(slotId to Slot(id = slotId, nodeId = rootId, name = "children", children = emptyList())),
            themeRef = ThemeRef.Inherit,
        )
    }

    private fun templateDocumentWithPath(path: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root-path",
        nodes = mapOf(
            "root-path" to Node(id = "root-path", type = "root", slots = listOf("slot-path")),
            "text-path" to Node(
                id = "text-path",
                type = "text",
                props = mapOf(
                    "content" to mapOf(
                        "type" to "doc",
                        "content" to listOf(
                            mapOf(
                                "type" to "paragraph",
                                "content" to listOf(
                                    mapOf("type" to "expression", "attrs" to mapOf("expression" to path)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        slots = mapOf(
            "slot-path" to Slot(id = "slot-path", nodeId = "root-path", name = "children", children = listOf("text-path")),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
