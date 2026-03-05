package app.epistola.suite.templates.commands

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.variants.ListVariants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ImportTemplatesTest : CoreIntegrationTestBase() {

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

            val variants = ListVariants(templateId = app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of(slug), tenantId)).query()
            assertThat(variants).hasSize(1)
            assertThat(variants[0].isDefault).isTrue()
            assertThat(variants[0].id.value).isEqualTo("default")
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

            val templateId = app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of(slug), tenantId)
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
            val templateId = app.epistola.suite.common.ids.TemplateId(app.epistola.suite.common.ids.TemplateKey.of(slug), tenantId)

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
}
