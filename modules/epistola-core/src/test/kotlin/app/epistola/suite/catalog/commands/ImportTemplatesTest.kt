// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ImportTemplatesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val templateModel = TestTemplateBuilder.buildMinimal()

    @Test
    fun `import with one default variant creates variant with correct flag`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val slug = TestIdHelpers.nextTemplateId().value
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Test Template",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()

            assertThat(results).hasSize(1)
            assertThat(results[0].status).isEqualTo(ImportStatus.CREATED)

            val variants = ListVariants(templateId = app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of(slug), CatalogId.default(tenantId))).query()
            assertThat(variants).hasSize(1)
            assertThat(variants[0].isDefault).isTrue()
            assertThat(variants[0].id.value).isEqualTo("default")
        }
    }

    @Test
    fun `import falls back to the variant slug for a title-less variant`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val slug = TestIdHelpers.nextTemplateId().value
            ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Fallback Template",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "english-invoice", title = null, attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()

            val variants = ListVariants(templateId = TemplateId(TemplateKey.of(slug), CatalogId.default(tenantId))).query()
            assertThat(variants).hasSize(1)
            assertThat(variants[0].title).isEqualTo("english-invoice")
        }
    }

    @Test
    fun `import with multiple variants marks only the declared default`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val slug = TestIdHelpers.nextTemplateId().value
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Multi Variant",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "dutch", title = "Nederlands", attributes = mapOf("language" to "nl"), templateModel = null, isDefault = true),
                            ImportVariantInput(id = "english", title = "English", attributes = mapOf("language" to "en"), templateModel = null, isDefault = false),
                        ),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()

            assertThat(results[0].status).isNotEqualTo(ImportStatus.FAILED)

            val templateId = app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of(slug), CatalogId.default(tenantId))
            val variants = ListVariants(templateId = templateId).query()
            assertThat(variants).hasSize(2)

            val defaults = variants.filter { it.isDefault }
            assertThat(defaults).hasSize(1)
            assertThat(defaults[0].id.value).isEqualTo("dutch")
        }
    }

    @Test
    fun `re-import cleans up orphan variants from previous import`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val slug = TestIdHelpers.nextTemplateId().value
            val templateId = app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of(slug), CatalogId.default(tenantId))

            // First import: two variants
            ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Cleanup Test",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                            ImportVariantInput(id = "english", title = "English", attributes = emptyMap(), templateModel = null, isDefault = false),
                        ),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()

            assertThat(ListVariants(templateId = templateId).query()).hasSize(2)

            // Second import: only one variant — orphan "english" should be removed
            ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Cleanup Test",
                        version = "1.1.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()

            val remaining = ListVariants(templateId = templateId).query()
            assertThat(remaining).hasSize(1)
            assertThat(remaining[0].id.value).isEqualTo("default")
        }
    }

    @Test
    fun `validation error when zero variants have isDefault true`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val slug = TestIdHelpers.nextTemplateId().value
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "No Default",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "variant-a", title = "A", attributes = emptyMap(), templateModel = null, isDefault = false),
                        ),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()

            assertThat(results).hasSize(1)
            assertThat(results[0].status).isEqualTo(ImportStatus.FAILED)
            assertThat(results[0].errorMessage).contains("isDefault=true")
        }
    }

    @Test
    fun `validation error when multiple variants have isDefault true`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val slug = TestIdHelpers.nextTemplateId().value
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Multi Default",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "variant-a", title = "A", attributes = emptyMap(), templateModel = null, isDefault = true),
                            ImportVariantInput(id = "variant-b", title = "B", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()

            assertThat(results).hasSize(1)
            assertThat(results[0].status).isEqualTo(ImportStatus.FAILED)
            assertThat(results[0].errorMessage).contains("isDefault=true")
        }
    }

    @Test
    fun `validation error when variants list is empty`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val slug = TestIdHelpers.nextTemplateId().value
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Empty Variants",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = emptyList(),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()

            assertThat(results).hasSize(1)
            assertThat(results[0].status).isEqualTo(ImportStatus.FAILED)
            assertThat(results[0].errorMessage).contains("at least one variant")
        }
    }

    @Test
    fun `import multiple templates with same variant key and publish succeeds`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val envKey = TestIdHelpers.nextEnvironmentId()
            CreateEnvironment(
                id = EnvironmentId(envKey, tenantId),
                name = "Production",
            ).execute()

            val slug1 = TestIdHelpers.nextTemplateId().value
            val slug2 = TestIdHelpers.nextTemplateId().value
            val slug3 = TestIdHelpers.nextTemplateId().value

            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug1,
                        name = "Template One",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = listOf(envKey.value),
                    ),
                    ImportTemplateInput(
                        slug = slug2,
                        name = "Template Two",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = listOf(envKey.value),
                    ),
                    ImportTemplateInput(
                        slug = slug3,
                        name = "Template Three",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = listOf(envKey.value),
                    ),
                ),
            ).execute()

            assertThat(results).hasSize(3)
            assertThat(results).allSatisfy { result ->
                assertThat(result.status).isNotEqualTo(ImportStatus.FAILED)
                assertThat(result.publishedTo).containsExactly(envKey.value)
            }
        }
    }

    @Test
    fun `import without data model creates contract version and allows creating variants`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val slug = TestIdHelpers.nextTemplateId().value
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "No DataModel Template",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()

            assertThat(results[0].status).isEqualTo(ImportStatus.CREATED)

            // Creating a variant requires a contract version — this would NPE before the fix
            val templateId = TemplateId(TemplateKey.of(slug), CatalogId.default(tenantId))
            val variantId = VariantId(VariantKey.of("new-variant"), templateId)
            val variant = CreateVariant(
                id = variantId,
                title = "New Variant",
                description = null,
            ).execute()

            assertThat(variant).isNotNull
        }
    }

    @Test
    fun `import and publish works end-to-end`() {
        val tenant = createTenant("Import Test")
        val tenantId = TenantId(tenant.id)

        withMediator {
            // Create an environment first
            val envKey = TestIdHelpers.nextEnvironmentId()
            CreateEnvironment(
                id = EnvironmentId(envKey, tenantId),
                name = "Production",
            ).execute()

            val slug = TestIdHelpers.nextTemplateId().value
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Publish Test",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModel,
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = listOf(envKey.value),
                    ),
                ),
            ).execute()

            assertThat(results).hasSize(1)
            assertThat(results[0].status).isNotEqualTo(ImportStatus.FAILED)
            assertThat(results[0].publishedTo).containsExactly(envKey.value)
        }
    }

    @Test
    fun `import stores referenced paths for the imported model`() {
        val tenant = createTenant("Import Paths Test")
        val tenantId = TenantId(tenant.id)
        val slug = TestIdHelpers.nextTemplateId().value

        withMediator {
            val results = ImportTemplates(
                tenantId = tenantId,
                templates = listOf(
                    ImportTemplateInput(
                        slug = slug,
                        name = "Import Paths",
                        version = "1.0.0",
                        dataModel = null,
                        dataExamples = emptyList(),
                        templateModel = templateModelWithPath("invoice.number"),
                        variants = listOf(
                            ImportVariantInput(id = "default", title = "Default", attributes = emptyMap(), templateModel = null, isDefault = true),
                        ),
                        publishTo = emptyList(),
                    ),
                ),
            ).execute()
            assertThat(results.single().status).isEqualTo(ImportStatus.CREATED)
        }

        val referencedPaths = jdbi.withHandle<String, Exception> { handle ->
            handle.createQuery(
                """
                SELECT referenced_paths::text
                FROM template_versions
                WHERE tenant_key = :tenantKey AND catalog_key = 'default'
                  AND template_key = :templateKey AND variant_key = 'default'
                ORDER BY id DESC LIMIT 1
                """,
            )
                .bind("tenantKey", tenant.id)
                .bind("templateKey", slug)
                .mapTo(String::class.java)
                .one()
        }
        assertThat(referencedPaths).isEqualTo("""["invoice.number"]""")
    }

    private fun templateModelWithPath(path: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "text" to Node(
                id = "text",
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
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("text")),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
