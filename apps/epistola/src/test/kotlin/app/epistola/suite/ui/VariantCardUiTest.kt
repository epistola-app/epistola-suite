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

    /**
     * The client-side attribute filter (`templates/detail/variants.html`)
     * hides non-matching cards with inline `style="display: none"`. Selecting
     * only the *shown* cards structurally — and asserting with a web-first
     * locator that re-queries — replaces the query-time `:visible` pseudo.
     */
    private val shownCards get() = page.locator(".variant-card:not([style*='display: none'])")

    @Test
    fun `variant cards render in grid layout`() {
        val (tenant, template) = withMediator { createTenantAndTemplate() }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")

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

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")

        val cards = page.locator(".variant-card")
        assertThat(cards).hasCount(2)
        assertThat(cards.first()).hasClass(Pattern.compile(".*variant-card-default.*"))
    }

    @Test
    fun `attribute filter dropdowns appear for each used attribute definition`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            val templateId = TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("lang"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("brand"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
            ).execute()
            // The filter bar lists only attributes that at least one variant
            // actually uses — defining a value without using it would clutter
            // every template's filter bar with attributes that can never
            // filter anything. Create variants that exercise both.
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                title = "English Acme",
                description = null,
                attributes = mapOf("lang" to "en", "brand" to "acme"),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")

        val filterBar = page.locator("#variant-filter-bar")
        assertThat(filterBar).isVisible()

        // Filter keys are catalog-qualified after the catalog-qualified-
        // references rollout (`<catalog>.<slug>`). The variant attribute
        // map was stored as bare `lang`/`brand`; the handler resolves these
        // to descriptors in the default catalog and surfaces them
        // qualified.
        val langSelect = filterBar.locator("select[data-filter-key='default.lang']")
        assertThat(langSelect).isVisible()
        assertThat(langSelect.locator("option")).hasCount(3) // All + en + nl

        val brandSelect = filterBar.locator("select[data-filter-key='default.brand']")
        assertThat(brandSelect).isVisible()
        assertThat(brandSelect.locator("option")).hasCount(3) // All + acme + globex
    }

    @Test
    fun `filtering by attribute hides non-matching cards`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("lang"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()

            // Default variant has no attributes. Create two more with different languages.
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "English",
                description = null,
                attributes = mapOf("lang" to "en"),
            ).execute()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "Dutch",
                description = null,
                attributes = mapOf("lang" to "nl"),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")

        val cards = page.locator(".variant-card")
        assertThat(cards).hasCount(3) // default + en + nl

        // Filter to "en"
        page.locator("select[data-filter-key='default.lang']").selectOption("en")

        // Only the English card should be shown
        assertThat(shownCards).hasCount(1)
        assertThat(shownCards.first().locator(".variant-card-title")).hasText("English")
    }

    @Test
    fun `clearing filter shows all cards`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("lang"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "English",
                description = null,
                attributes = mapOf("lang" to "en"),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")

        // Apply filter
        page.locator("select[data-filter-key='default.lang']").selectOption("en")
        assertThat(shownCards).hasCount(1)

        // Clear filter
        page.locator("select[data-filter-key='default.lang']").selectOption("")
        assertThat(shownCards).hasCount(2)
    }

    @Test
    fun `combining multiple filters works`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("lang"), CatalogId.default(TenantId(tenant.id))),
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
                attributes = mapOf("lang" to "en", "brand" to "acme"),
            ).execute()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "EN Globex",
                description = null,
                attributes = mapOf("lang" to "en", "brand" to "globex"),
            ).execute()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))),
                title = "NL Acme",
                description = null,
                attributes = mapOf("lang" to "nl", "brand" to "acme"),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")
        assertThat(page.locator(".variant-card")).hasCount(4) // default + 3

        // Filter: language=en AND brand=acme
        page.locator("select[data-filter-key='default.lang']").selectOption("en")
        page.locator("select[data-filter-key='default.brand']").selectOption("acme")

        assertThat(shownCards).hasCount(1)
        assertThat(shownCards.first().locator(".variant-card-title")).hasText("EN Acme")
    }

    @Test
    fun `create variant via HTMX adds a new card`() {
        val (tenant, template) = withMediator { createTenantAndTemplate() }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")
        assertThat(page.locator(".variant-card")).hasCount(1)

        val dialog = page.openDialogByTrigger(
            page.locator("button:has-text('New Variant')"),
            "#create-variant-dialog",
        )
        dialog.locator("#slug").fill("new-variant")
        dialog.locator("#title").fill("New Variant")
        dialog.locator("button[type='submit']").click()
        page.htmxSettle()

        // Web-first: retries until the swapped-in card is present.
        assertThat(page.locator(".variant-card[data-variant-id='new-variant']")).isVisible()
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

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")
        assertThat(page.locator(".variant-card")).hasCount(2)

        // Click delete on the non-default variant (opens confirm dialog).
        val nonDefaultCard = page.locator(".variant-card:not(.variant-card-default)")
        val confirm = page.openDialogByTrigger(
            nonDefaultCard.locator("button.ep-btn-destructive"),
            "#confirm-dialog",
        )
        confirm.locator("button.ep-btn-destructive").click()
        page.htmxSettle()

        assertThat(page.locator(".variant-card")).hasCount(1)
    }

    @Test
    fun `edit variant dialog opens when variant has no attributes`() {
        // Regression: SpEL couldn't resolve `contains(String)` on Kotlin's
        // `EmptySet` singleton, so opening the edit dialog on a variant
        // whose attributes map was empty crashed the template engine.
        // Wrapping `presentRawKeys` / `presentQualifiedKeys` in a normal
        // `LinkedHashSet<String>` fixes it; this test guards the fix.
        val (tenant, template) = withMediator { createTenantAndTemplate() }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")
        assertThat(page.locator(".variant-card")).hasCount(1)

        // Open edit dialog on the default variant — it has empty attributes
        // because `createTenantAndTemplate()` doesn't seed any.
        val dialog = page.openDialogByTrigger(
            page.locator(".variant-card button[title='Edit variant']"),
            "#edit-variant-dialog",
        )

        // Dialog rendered without error and surfaces the add-attribute
        // picker (the empty-state UI affordance).
        assertThat(dialog.locator("#edit-add-attr")).isVisible()
    }

    @Test
    fun `filter bar appears after HTMX create of variant with attributes`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("lang"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")
        assertThat(page.locator("#variant-filter-bar")).isHidden()

        val dialog = page.openDialogByTrigger(
            page.locator("button:has-text('New Variant')"),
            "#create-variant-dialog",
        )
        dialog.locator("#slug").fill(TestIdHelpers.nextVariantId().value)
        dialog.locator("#title").fill("With Attr")
        dialog.locator("#create-add-attr").selectOption("default.lang")
        dialog.locator("button:has-text('Add')").click()
        dialog.locator("select[name='attr_default.lang']").selectOption("en")
        dialog.locator("button[type='submit']").click()
        page.htmxSettle()

        assertThat(page.locator("#variant-filter-bar")).isVisible()
        assertThat(page.locator("select[data-filter-key='default.lang']")).isVisible()
    }

    @Test
    fun `filter bar disappears after HTMX delete of last variant with attributes`() {
        val (tenant, template) = withMediator {
            val (tenant, template) = createTenantAndTemplate()
            val templateId = TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("lang"), CatalogId.default(TenantId(tenant.id))),
                displayName = "Language",
                allowedValues = listOf("en", "nl"),
            ).execute()
            CreateVariant(
                id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                title = "With Attr",
                description = null,
                attributes = mapOf("lang" to "en"),
            ).execute()
            tenant to template
        }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")
        assertThat(page.locator("#variant-filter-bar")).isVisible()

        // Delete the non-default variant.
        val nonDefaultCard = page.locator(".variant-card:not(.variant-card-default)")
        val confirm = page.openDialogByTrigger(
            nonDefaultCard.locator("button.ep-btn-destructive"),
            "#confirm-dialog",
        )
        confirm.locator("button.ep-btn-destructive").click()
        page.htmxSettle()

        assertThat(page.locator(".variant-card")).hasCount(1)
        assertThat(page.locator("#variant-filter-bar")).isHidden()
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
