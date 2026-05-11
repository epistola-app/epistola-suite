package app.epistola.suite.ui

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeListEntryHidden
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * End-to-end UI coverage for the code lists feature. Pairs with the headless
 * backend tests in [app.epistola.suite.attributes.codelists] — this class
 * exercises the browser-facing surface: the three-way constraint picker on
 * the attribute form, the inline entries editor on the new-code-list page,
 * the variant dropdown rendering with `code — label`, and the per-entry
 * hide toggle via HTMX.
 *
 * Setup is split between mediator commands (when the UI flow under test
 * isn't the focus — e.g. tenant + template creation, or seeding a code list
 * before a different page is exercised) and the actual UI (for the flow
 * being tested). Each test method is responsible for at most one UI flow so
 * a failure points at one place.
 */
class CodeListUiTest : BasePlaywrightTest() {

    @Test
    fun `create INLINE code list via new form lands on detail page with entries rendered`() {
        val tenant = withMediator { createUiTenant() }

        val responses = mutableListOf<String>()
        page.onResponse { r ->
            if (r.request().method() == "POST") {
                responses.add("POST ${r.url()} → ${r.status()}")
            }
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/code-lists/new")

        // The inline editor's bootstrap script auto-seeds one empty row when
        // there's no prior submission, so we don't need to click add for the
        // first entry — only for the second.
        page.waitForSelector("#entries-tbody tr")

        page.locator("#displayName").fill("Locales")
        page.locator("#slug").fill("locales")

        val rows = page.locator("#entries-tbody tr")
        rows.first().locator("input[placeholder='code']").fill("en")
        rows.first().locator("input[placeholder='Label']").fill("English")
        page.locator("#add-entry-btn").click()
        rows.nth(1).locator("input[placeholder='code']").fill("nl")
        rows.nth(1).locator("input[placeholder='Label']").fill("Dutch")

        // The page has multiple `type="submit"` buttons (form + nav-bar logout
        // forms). Match by visible text to disambiguate.
        page.locator("button:has-text('Create code list')").click()
        try {
            page.waitForURL(Pattern.compile(".*/code-lists/default/locales$"))
        } catch (e: Exception) {
            System.err.println("=== URL after submit: ${page.url()} ===")
            System.err.println("=== POST responses ===")
            responses.forEach { System.err.println(it) }
            System.err.println("=== entriesJson value ===")
            System.err.println(page.locator("#entriesJson").inputValue())
            System.err.println("=== form errors ===")
            page.locator(".form-error").all().forEach { System.err.println(it.textContent()) }
            throw e
        }

        // Detail page: both entries listed in the entries table.
        val entryRows = page.locator("#entries-tbody tr")
        assertThat(entryRows).hasCount(2)
        assertThat(page.locator("#entry-row-en")).isVisible()
        assertThat(page.locator("#entry-row-nl")).isVisible()
    }

    @Test
    fun `bind attribute to code list via three-way picker`() {
        val tenant = withMediator {
            val t = createUiTenant()
            val tenantId = TenantId(t.id)
            CreateCodeList(
                id = CodeListId(CodeListKey.of("locales"), CatalogId.default(tenantId)),
                displayName = "Locales",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("en", "English"), CodeListEntry("nl", "Dutch")),
            ).execute()
            t
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/attributes/new")

        page.locator("#displayName").fill("Language")
        page.locator("#slug").fill("language")

        // Pick "Bound to code list" — the inline-values pane should hide and
        // the code-list pane should reveal.
        page.locator("input[name='constraintKind'][value='code-list']").check()
        assertThat(page.locator("[data-constraint-pane='inline']")).isHidden()
        assertThat(page.locator("[data-constraint-pane='code-list']")).isVisible()

        // The select carries "<catalog>/<slug>" packed values.
        page.locator("#codeList").selectOption("default/locales")

        page.locator("button:has-text('Create Attribute')").click()
        page.waitForURL(Pattern.compile(".*/attributes$"))

        // The attribute row's "Allowed values" cell renders "→ default/locales"
        // when the attribute is bound to a code list. (See attributes/list.html.)
        assertThat(page.getByText("→ default/locales").first()).isVisible()
    }

    @Test
    fun `variant create dialog renders code-list entries as code dash label`() {
        val (tenant, template) = withMediator {
            val t = createUiTenant()
            val tenantId = TenantId(t.id)
            val catalogId = CatalogId.default(tenantId)

            CreateCodeList(
                id = CodeListId(CodeListKey.of("locales"), catalogId),
                displayName = "Locales",
                sourceType = CodeListSource.INLINE,
                entries = listOf(
                    CodeListEntry("en-US", "English (United States)"),
                    CodeListEntry("nl-NL", "Dutch (Netherlands)"),
                ),
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("locale"), catalogId),
                displayName = "Locale",
                codeListCatalogKey = catalogId.key,
                codeListSlug = CodeListKey.of("locales"),
            ).execute()

            val template = CreateDocumentTemplate(
                id = TemplateId(TestIdHelpers.nextTemplateId(), catalogId),
                name = "UI Test Template",
            ).execute()
            t to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")
        page.locator("button:has-text('New Variant')").click()
        page.waitForSelector("#create-variant-dialog[open]")

        val select = page.locator("#create-attr_locale")
        assertThat(select).isVisible()

        // Options: the "- Not set -" placeholder + the two bound entries.
        // Each option's visible text is "<code> — <label>".
        assertThat(select.locator("option")).hasCount(3)
        assertThat(select.locator("option[value='en-US']")).hasText("en-US — English (United States)")
        assertThat(select.locator("option[value='nl-NL']")).hasText("nl-NL — Dutch (Netherlands)")
    }

    @Test
    fun `hidden entry disappears from variant dropdown after page reload`() {
        val (tenant, template) = withMediator {
            val t = createUiTenant()
            val tenantId = TenantId(t.id)
            val catalogId = CatalogId.default(tenantId)
            val codeListId = CodeListId(CodeListKey.of("statuses"), catalogId)

            CreateCodeList(
                id = codeListId,
                displayName = "Statuses",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("active", "Active"), CodeListEntry("legacy", "Legacy")),
            ).execute()
            // Hide the "legacy" entry via the command — this test isn't about
            // the hide-toggle UI; that's covered separately.
            UpdateCodeListEntryHidden(codeListId, "legacy", hidden = true).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("status"), catalogId),
                displayName = "Status",
                codeListCatalogKey = catalogId.key,
                codeListSlug = codeListId.key,
            ).execute()

            val template = CreateDocumentTemplate(
                id = TemplateId(TestIdHelpers.nextTemplateId(), catalogId),
                name = "UI Test Template",
            ).execute()
            t to template
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/default/${template.id}")
        page.locator("button:has-text('New Variant')").click()
        page.waitForSelector("#create-variant-dialog[open]")

        val statusOptions = page.locator("#create-attr_status option")
        // "- Not set -" + only "active" (hidden "legacy" is filtered).
        assertThat(statusOptions).hasCount(2)
        assertThat(page.locator("#create-attr_status option[value='active']")).hasCount(1)
        assertThat(page.locator("#create-attr_status option[value='legacy']")).hasCount(0)
    }

    @Test
    fun `code list filter on list page narrows by catalog`() {
        val tenant = withMediator {
            val t = createUiTenant()
            val tenantId = TenantId(t.id)
            CreateCodeList(
                id = CodeListId(CodeListKey.of("locales"), CatalogId.default(tenantId)),
                displayName = "Locales",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("en", "English")),
            ).execute()
            t
        }

        page.navigate("${baseUrl()}/tenants/${tenant.id}/code-lists")

        val rows = page.locator("table.ep-table tbody tr")
        assertThat(rows).hasCount(1)
        assertThat(rows.first().locator("a").first()).hasText("locales")
    }

    /**
     * Helper to create a fresh tenant for each UI test. Uses a nanosecond-
     * suffixed slug so parallel test runs don't collide on the unique tenant
     * key.
     */
    private fun createUiTenant(): Tenant {
        val tenantKey = TenantKey.of("test-cl-${System.nanoTime()}")
        return CreateTenant(id = tenantKey, name = "Code List UI Test").execute()
    }
}
