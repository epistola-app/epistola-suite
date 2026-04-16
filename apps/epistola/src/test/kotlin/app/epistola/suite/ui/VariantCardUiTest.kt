package app.epistola.suite.ui

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class VariantCardUiTest : BasePlaywrightTest() {

    @Test
    fun `variant cards render in grid layout`() {
        val (tenant, template) = withMediator { createTenantAndTemplate() }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")

        assertThat(page.locator(".variant-card")).hasCount(1)
        assertThat(page.locator("table.ep-table")).hasCount(0)
    }

    @Test
    fun `default variant renders first with distinct styling`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "Extra Variant",
                description = null,
                attributes = emptyMap(),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")

        val cards = page.locator(".variant-card")
        assertThat(cards).hasCount(2)
        assertThat(cards.first()).hasClass(Pattern.compile(".*variant-card-default.*"))
    }

    @Test
    fun `attribute filter dropdowns appear for each attribute definition`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("brand"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")

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
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()

            // Default variant has no attributes. Create two more with different languages.
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "English",
                description = null,
                attributes = mapOf("language" to "en"),
            ).execute()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "Dutch",
                description = null,
                attributes = mapOf("language" to "nl"),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")

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
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "English",
                description = null,
                attributes = mapOf("language" to "en"),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")

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
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("brand"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
            ).execute()

            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "EN Acme",
                description = null,
                attributes = mapOf("language" to "en", "brand" to "acme"),
            ).execute()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "EN Globex",
                description = null,
                attributes = mapOf("language" to "en", "brand" to "globex"),
            ).execute()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "NL Acme",
                description = null,
                attributes = mapOf("language" to "nl", "brand" to "acme"),
            ).execute()
            tenant to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")
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

        // Capture all responses for debugging
        val allResponses = mutableListOf<String>()
        val consoleLogs = mutableListOf<String>()
        val networkErrors = mutableListOf<String>()
        page.onConsoleMessage { msg -> consoleLogs.add("[${msg.type()}] ${msg.text()}") }
        page.onResponse { resp ->
            allResponses.add("${resp.status()} ${resp.request().method()} ${resp.url()}")
            if (resp.request().method() == "POST") {
                allResponses.add("  POST RESPONSE BODY: ${resp.text().take(2000)}")
            }
            if (resp.status() >= 400) {
                networkErrors.add("${resp.status()} ${resp.url()} body=${resp.text().take(500)}")
            }
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")
        assertThat(page.locator(".variant-card")).hasCount(1)

        // Open create dialog
        page.locator("button:has-text('New Variant')").click()
        page.waitForSelector("#create-variant-dialog[open]")
        page.locator("#create-variant-dialog #slug").fill("new-variant")
        page.locator("#create-variant-dialog #title").fill("New Variant")
        page.locator("#create-variant-dialog button[type='submit']").click()

        // Wait for HTMX swap
        try {
            page.waitForSelector(".variant-card[data-variant-id='new-variant']")
        } catch (e: Exception) {
            System.err.println("=== URL: ${page.url()} ===")
            System.err.println("=== CONSOLE LOGS (${consoleLogs.size}) ===")
            consoleLogs.forEach { System.err.println(it) }
            System.err.println("=== NETWORK ERRORS (${networkErrors.size}) ===")
            networkErrors.forEach { System.err.println(it) }
            System.err.println("=== VARIANTS SECTION ===")
            try {
                System.err.println(page.locator("#variants-section").innerHTML())
            } catch (_: Exception) {
                System.err.println("(not found)")
            }
            System.err.println("=== ALL VARIANT CARDS ===")
            System.err.println("count=${page.locator(".variant-card").count()}")
            for (i in 0 until page.locator(".variant-card").count()) {
                System.err.println("card[$i] data-variant-id=${page.locator(".variant-card").nth(i).getAttribute("data-variant-id")}")
            }
            System.err.println("=== ALL RESPONSES (${allResponses.size}) ===")
            allResponses.forEach { System.err.println(it) }
            throw e
        }
        assertThat(page.locator(".variant-card")).hasCount(2)
    }

    @Test
    fun `delete variant via HTMX removes the card`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "To Delete",
                description = null,
                attributes = emptyMap(),
            ).execute()
            tenant to template
        }

        // Capture console and network for debugging
        val consoleLogs = mutableListOf<String>()
        val networkErrors = mutableListOf<String>()
        page.onConsoleMessage { msg -> consoleLogs.add("[${msg.type()}] ${msg.text()}") }
        page.onResponse { resp ->
            if (resp.status() >= 400) {
                networkErrors.add("${resp.status()} ${resp.url()} body=${resp.text().take(500)}")
            }
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")
        assertThat(page.locator(".variant-card")).hasCount(2)

        // Click delete on the non-default variant (opens confirm dialog)
        val nonDefaultCard = page.locator(".variant-card:not(.variant-card-default)")
        nonDefaultCard.locator("button.btn-ghost-destructive").click()

        // Confirm in the custom dialog
        page.waitForSelector("#confirm-dialog[open]")
        page.locator("#confirm-dialog button.btn-destructive").click()

        // Wait for HTMX swap to complete
        try {
            page.waitForFunction("document.querySelectorAll('.variant-card').length === 1")
        } catch (e: Exception) {
            System.err.println("=== URL: ${page.url()} ===")
            System.err.println("=== CONSOLE LOGS (${consoleLogs.size}) ===")
            consoleLogs.forEach { System.err.println(it) }
            System.err.println("=== NETWORK ERRORS (${networkErrors.size}) ===")
            networkErrors.forEach { System.err.println(it) }
            System.err.println("=== FULL PAGE BODY ===")
            System.err.println(page.content().take(5000))
            throw e
        }
        assertThat(page.locator(".variant-card")).hasCount(1)
    }

    /**
     * Helper to create a tenant + template. Creating a template auto-creates a default variant.
     */
    private fun createTenantAndTemplate(): Pair<Tenant, DocumentTemplate> {
        val tenantKey = TenantKey.of("test-ui-tenant-${System.nanoTime()}")
        val tenant = CreateTenant(id = tenantKey, name = "UI Test Tenant").execute()
        val tenantId = TenantId(tenant.id)
        val template = CreateDocumentTemplate(
            id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)),
            name = "UI Test Template",
        ).execute()
        return tenant to template
    }
}
