package app.epistola.suite.templates.commands.variants

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
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
        val tenantId = TenantId(tenant.id)

        withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Invoice",
            ).execute()

            val variants = ListVariants(templateId = templateId).query()
            assertThat(variants).hasSize(1)
            assertThat(variants[0].isDefault).isTrue()
        }
    }

    @Test
    fun `subsequent variants are not default`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Invoice",
            ).execute()

            val secondVariant = CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                title = "English",
                description = null,
            ).execute()!!

            assertThat(secondVariant.isDefault).isFalse()
        }
    }

    @Test
    fun `set-default changes which variant is default`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Invoice",
            ).execute()

            val secondVariant = CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                title = "English",
                description = null,
            ).execute()!!

            val result = SetDefaultVariant(
                variantId = VariantId(secondVariant.id, templateId),
            ).execute()

            assertThat(result).isNotNull
            assertThat(result!!.isDefault).isTrue()

            // Verify old default lost its flag
            val variants = ListVariants(templateId = templateId).query()
            val defaults = variants.filter { it.isDefault }
            assertThat(defaults).hasSize(1)
            assertThat(defaults[0].id).isEqualTo(secondVariant.id)
        }
    }

    @Test
    fun `blocks deletion of default variant`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Invoice",
            ).execute()

            val variants = ListVariants(templateId = templateId).query()
            val defaultVariant = variants.first()

            assertThatThrownBy {
                DeleteVariant(
                    variantId = VariantId(defaultVariant.id, templateId),
                ).execute()
            }.isInstanceOf(DefaultVariantDeletionException::class.java)
        }
    }

    @Test
    fun `allows deletion of non-default variant`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Invoice",
            ).execute()

            val secondVariant = CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                title = "English",
                description = null,
            ).execute()!!

            val deleted = DeleteVariant(
                variantId = VariantId(secondVariant.id, templateId),
            ).execute()

            assertThat(deleted).isTrue()
        }
    }

    @Test
    fun `allows deletion after reassigning default`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
            val template = CreateDocumentTemplate(
                id = templateId,
                name = "Invoice",
            ).execute()

            val variants = ListVariants(templateId = templateId).query()
            val originalDefault = variants.first()

            val secondVariant = CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                title = "English",
                description = null,
            ).execute()!!

            // Reassign default to second variant
            SetDefaultVariant(
                variantId = VariantId(secondVariant.id, templateId),
            ).execute()

            // Now original can be deleted
            val deleted = DeleteVariant(
                variantId = VariantId(originalDefault.id, templateId),
            ).execute()

            assertThat(deleted).isTrue()
        }
    }
}
