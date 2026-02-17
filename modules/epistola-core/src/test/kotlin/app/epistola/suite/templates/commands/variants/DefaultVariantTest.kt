package app.epistola.suite.templates.commands.variants

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.queries.variants.ListVariants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DefaultVariantTest : CoreIntegrationTestBase() {

    @Test
    fun `first variant created with template is automatically the default`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val template = CreateDocumentTemplate(
                id = TestIdHelpers.nextTemplateId(),
                tenantId = tenant.id,
                name = "Invoice",
            ).execute()

            val variants = ListVariants(tenantId = tenant.id, templateId = template.id).query()
            assertThat(variants).hasSize(1)
            assertThat(variants[0].isDefault).isTrue()
        }
    }

    @Test
    fun `subsequent variants are not default`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val template = CreateDocumentTemplate(
                id = TestIdHelpers.nextTemplateId(),
                tenantId = tenant.id,
                name = "Invoice",
            ).execute()

            val secondVariant = CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "English",
                description = null,
            ).execute()!!

            assertThat(secondVariant.isDefault).isFalse()
        }
    }

    @Test
    fun `set-default changes which variant is default`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val template = CreateDocumentTemplate(
                id = TestIdHelpers.nextTemplateId(),
                tenantId = tenant.id,
                name = "Invoice",
            ).execute()

            val secondVariant = CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "English",
                description = null,
            ).execute()!!

            val result = SetDefaultVariant(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = secondVariant.id,
            ).execute()

            assertThat(result).isNotNull
            assertThat(result!!.isDefault).isTrue()

            // Verify old default lost its flag
            val variants = ListVariants(tenantId = tenant.id, templateId = template.id).query()
            val defaults = variants.filter { it.isDefault }
            assertThat(defaults).hasSize(1)
            assertThat(defaults[0].id).isEqualTo(secondVariant.id)
        }
    }

    @Test
    fun `blocks deletion of default variant`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val template = CreateDocumentTemplate(
                id = TestIdHelpers.nextTemplateId(),
                tenantId = tenant.id,
                name = "Invoice",
            ).execute()

            val variants = ListVariants(tenantId = tenant.id, templateId = template.id).query()
            val defaultVariant = variants.first()

            assertThatThrownBy {
                DeleteVariant(
                    tenantId = tenant.id,
                    templateId = template.id,
                    variantId = defaultVariant.id,
                ).execute()
            }.isInstanceOf(DefaultVariantDeletionException::class.java)
        }
    }

    @Test
    fun `allows deletion of non-default variant`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val template = CreateDocumentTemplate(
                id = TestIdHelpers.nextTemplateId(),
                tenantId = tenant.id,
                name = "Invoice",
            ).execute()

            val secondVariant = CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "English",
                description = null,
            ).execute()!!

            val deleted = DeleteVariant(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = secondVariant.id,
            ).execute()

            assertThat(deleted).isTrue()
        }
    }

    @Test
    fun `allows deletion after reassigning default`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val template = CreateDocumentTemplate(
                id = TestIdHelpers.nextTemplateId(),
                tenantId = tenant.id,
                name = "Invoice",
            ).execute()

            val variants = ListVariants(tenantId = tenant.id, templateId = template.id).query()
            val originalDefault = variants.first()

            val secondVariant = CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "English",
                description = null,
            ).execute()!!

            // Reassign default to second variant
            SetDefaultVariant(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = secondVariant.id,
            ).execute()

            // Now original can be deleted
            val deleted = DeleteVariant(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = originalDefault.id,
            ).execute()

            assertThat(deleted).isTrue()
        }
    }
}
