package app.epistola.suite.ui

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class VariantCardUiTest : BasePlaywrightTest() {

    @Test
    fun `variant cards render in grid layout`() {
        val (tenant, template) = withMediator { createTenantAndTemplate() }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}")

        assertThat(page.locator(".variant-card")).hasCount(1)
        assertThat(page.locator("table.ep-table")).hasCount(0)
    }

    @Test
    fun `default variant renders first with distinct styling`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "Extra Variant",
                description = null,
                attributes = emptyMap(),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}")

        val cards = page.locator(".variant-card")
        assertThat(cards).hasCount(2)
        assertThat(cards.first()).hasClass(Pattern.compile(".*variant-card-default.*"))
    }

    @Test
    fun `attribute filter dropdowns appear for each attribute definition`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            CreateAttributeDefinition(
                id = AttributeId.of("brand"),
                tenantId = tenant.id,
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}")

        val filterBar = page.locator("#variant-filter-bar")
        assertThat(filterBar).isVisible()

        val selects = filterBar.locator("select[data-filter-key]")
        assertThat(selects).hasCount(2)

        val languageSelect = filterBar.locator("select[data-filter-key='language']")
        assertThat(languageSelect).isVisible()
        assertThat(languageSelect.locator("option")).hasCount(3) // All + en + nl

        val brandSelect = filterBar.locator("select[data-filter-key='brand']")
        assertThat(brandSelect).isVisible()
        assertThat(brandSelect.locator("option")).hasCount(3) // All + acme + globex
    }

    @Test
    fun `filtering by attribute hides non-matching cards`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()

            // Default variant has no attributes. Create two more with different languages.
            CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "English",
                description = null,
                attributes = mapOf("language" to "en"),
            ).execute()
            CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "Dutch",
                description = null,
                attributes = mapOf("language" to "nl"),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}")

        val cards = page.locator(".variant-card")
        assertThat(cards).hasCount(3) // default + en + nl

        // Filter to "en"
        page.locator("select[data-filter-key='language']").selectOption("en")

        // Only the English card should be visible
        val visibleCards = page.locator(".variant-card:visible")
        assertThat(visibleCards).hasCount(1)
        assertThat(visibleCards.first().locator(".variant-card-title")).hasText("English")
    }

    @Test
    fun `clearing filter shows all cards`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "English",
                description = null,
                attributes = mapOf("language" to "en"),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}")

        // Apply filter
        page.locator("select[data-filter-key='language']").selectOption("en")
        assertThat(page.locator(".variant-card:visible")).hasCount(1)

        // Clear filter
        page.locator("select[data-filter-key='language']").selectOption("")
        assertThat(page.locator(".variant-card:visible")).hasCount(2)
    }

    @Test
    fun `combining multiple filters works`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            CreateAttributeDefinition(
                id = AttributeId.of("brand"),
                tenantId = tenant.id,
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
            ).execute()

            CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "EN Acme",
                description = null,
                attributes = mapOf("language" to "en", "brand" to "acme"),
            ).execute()
            CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "EN Globex",
                description = null,
                attributes = mapOf("language" to "en", "brand" to "globex"),
            ).execute()
            CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "NL Acme",
                description = null,
                attributes = mapOf("language" to "nl", "brand" to "acme"),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}")
        assertThat(page.locator(".variant-card")).hasCount(4) // default + 3

        // Filter: language=en AND brand=acme
        page.locator("select[data-filter-key='language']").selectOption("en")
        page.locator("select[data-filter-key='brand']").selectOption("acme")

        val visibleCards = page.locator(".variant-card:visible")
        assertThat(visibleCards).hasCount(1)
        assertThat(visibleCards.first().locator(".variant-card-title")).hasText("EN Acme")
    }

    @Test
    fun `create variant via HTMX adds a new card`() {
        val (tenant, template) = withMediator { createTenantAndTemplate() }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}")
        assertThat(page.locator(".variant-card")).hasCount(1)

        // Open create form
        page.locator("button:has-text('New Variant')").click()
        page.locator("#slug").fill("new-variant")
        page.locator("#title").fill("New Variant")
        page.locator("#create-variant-form button[type='submit']").click()

        // Wait for HTMX swap
        page.waitForSelector(".variant-card[data-variant-id='new-variant']")
        assertThat(page.locator(".variant-card")).hasCount(2)
    }

    @Test
    fun `delete variant via HTMX removes the card`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateVariant(
                id = TestIdHelpers.nextVariantId(),
                tenantId = tenant.id,
                templateId = template.id,
                title = "To Delete",
                description = null,
                attributes = emptyMap(),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}")
        assertThat(page.locator(".variant-card")).hasCount(2)

        // Click delete on the non-default variant (opens confirm dialog)
        val nonDefaultCard = page.locator(".variant-card:not(.variant-card-default)")
        nonDefaultCard.locator("button.btn-ghost-destructive").click()

        // Confirm in the custom dialog
        page.waitForSelector("#confirm-dialog[open]")
        page.locator("#confirm-dialog button.btn-destructive").click()

        // Wait for HTMX swap to complete
        page.waitForFunction("document.querySelectorAll('.variant-card').length === 1")
        assertThat(page.locator(".variant-card")).hasCount(1)
    }

    /**
     * Helper to create a tenant + template. Creating a template auto-creates a default variant.
     */
    private fun createTenantAndTemplate(): Pair<Tenant, DocumentTemplate> {
        val tenantId = TenantId.of("test-ui-tenant-${System.nanoTime()}")
        val tenant = CreateTenant(id = tenantId, name = "UI Test Tenant").execute()
        val template = CreateDocumentTemplate(
            id = TestIdHelpers.nextTemplateId(),
            tenantId = tenant.id,
            name = "UI Test Template",
        ).execute()
        return tenant to template
    }
}
