// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import java.util.regex.Pattern

/**
 * End-to-end coverage for `embed-bridge.js` (docs/embedding.md, ADR 0015) —
 * the demo-mode-only postMessage bridge. Runs in its own Spring context
 * (embedding turned on) so every other Playwright test class keeps embedding
 * off via [BasePlaywrightTest]'s default.
 *
 * `resource-changed` detection is deliberately client-side only (no Kotlin
 * involvement — see the ADR for why), derived by watching real
 * `htmx:afterRequest` events against the same URL convention the whole app
 * already uses. This test exercises the real htmx runtime end to end instead
 * of asserting against the source, since the risk here is in htmx's actual
 * event/header shapes (`pathInfo.requestPath`, `requestConfig.verb`,
 * `xhr.getResponseHeader`), not in this file's own logic.
 *
 * `window.postMessage` is monkey-patched (not a real cross-origin iframe) to
 * capture the bridge's outgoing calls — simpler than route-intercepting a
 * synthetic second origin, and sufficient here since the Playwright ("test"
 * profile) harness never exercises the CSP/`X-Frame-Options`/cookie
 * `SameSite` HTTP-header behavior anyway (`SecurityConfig`'s embedding
 * branches only run under `!test`); that logic is covered instead by the pure
 * unit tests in `EmbeddingPropertiesTest`.
 */
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
        "epistola.embedding.enabled=true",
        "epistola.embedding.allowed-parent-origins=https://embed-host.test",
    ],
)
class EmbeddingBridgeUiTest : BasePlaywrightTest() {

    private fun createTestTenant(): Tenant = createTenant("Embedding Bridge Test")

    /** Installed before the first navigation so it survives every full reload. */
    private fun installMessageCapture() {
        page.addInitScript(
            """
            (() => {
                if (window.__epistolaMessagesInstalled) return;
                window.__epistolaMessagesInstalled = true;
                window.__epistolaMessages = [];
                const original = window.postMessage.bind(window);
                window.postMessage = function (message, targetOrigin) {
                    window.__epistolaMessages.push({ message: message, targetOrigin: targetOrigin });
                    return original(message, targetOrigin);
                };
            })();
            """,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun capturedMessages(): List<Map<String, Any?>> = page.evaluate("() => window.__epistolaMessages") as? List<Map<String, Any?>> ?: emptyList()

    private fun latestMessageOfType(type: String): Map<String, Any?>? = capturedMessages().lastOrNull { (it["message"] as? Map<*, *>)?.get("type") == type }

    private fun createTemplateViaUi(tenant: Tenant, name: String, slug: String) {
        gotoAndReady("/tenants/${tenant.id}/templates/new")
        page.locator("#name").fill(name)
        page.locator("#slug").fill(slug)
        page.locator("[data-testid='create-form-submit']").click()
        page.htmxSettle()
    }

    @Test
    fun `navigated fires on initial load with a null resource for the list page`() {
        val tenant = createTestTenant()
        installMessageCapture()

        gotoAndReady("/tenants/${tenant.id}/templates")
        assertThat(page.locator("main")).isVisible()

        val navigated = latestMessageOfType("navigated")
        checkNotNull(navigated) { "expected a 'navigated' message, got: ${capturedMessages()}" }
        val message = navigated["message"] as Map<*, *>
        Assertions.assertThat(message["source"]).isEqualTo("epistola-suite")
        Assertions.assertThat(message["resource"]).isNull()
        Assertions.assertThat(navigated["targetOrigin"]).isEqualTo("https://embed-host.test")
    }

    @Test
    fun `creating a template through the UI fires a resource-changed create message, then a navigated message`() {
        val tenant = createTestTenant()
        installMessageCapture()

        createTemplateViaUi(tenant, "Embed Bridge Template", "embed-bridge-template")
        assertThat(page.locator("#page-title-text")).containsText("Embed Bridge Template")

        val changed = latestMessageOfType("resource-changed")
        checkNotNull(changed) { "expected a 'resource-changed' message, got: ${capturedMessages()}" }
        val changedMessage = changed["message"] as Map<*, *>
        val resource = changedMessage["resource"] as Map<*, *>
        Assertions.assertThat(changedMessage["operation"]).isEqualTo("create")
        Assertions.assertThat(resource["resourceType"]).isEqualTo("template")
        Assertions.assertThat(resource["tenantId"]).isEqualTo(tenant.id.value)
        Assertions.assertThat(resource["catalogKey"]).isEqualTo("default")
        Assertions.assertThat(resource["key"]).isEqualTo("embed-bridge-template")

        val navigated = latestMessageOfType("navigated")
        checkNotNull(navigated) { "expected a follow-up 'navigated' message after create" }
        val navigatedResource = (navigated["message"] as Map<*, *>)["resource"] as Map<*, *>
        Assertions.assertThat(navigatedResource["key"]).isEqualTo("embed-bridge-template")
    }

    @Test
    fun `renaming a template's settings fires a resource-changed update message`() {
        val tenant = createTestTenant()
        installMessageCapture()

        createTemplateViaUi(tenant, "Rename Source", "rename-source")

        gotoAndReady("/tenants/${tenant.id}/templates/default/rename-source/settings")
        val input = page.locator("[data-template-name-input]")
        input.fill("Renamed Via Embed Test")
        input.press("Enter")
        page.htmxSettle()

        val changed = latestMessageOfType("resource-changed")
        checkNotNull(changed) { "expected a 'resource-changed' message, got: ${capturedMessages()}" }
        val message = changed["message"] as Map<*, *>
        val resource = message["resource"] as Map<*, *>
        Assertions.assertThat(message["operation"]).isEqualTo("update")
        Assertions.assertThat(resource["resourceType"]).isEqualTo("template")
        Assertions.assertThat(resource["catalogKey"]).isEqualTo("default")
        Assertions.assertThat(resource["key"]).isEqualTo("rename-source")
    }

    @Test
    fun `deleting a template fires a resource-changed delete message via the flash query param`() {
        val tenant = createTestTenant()
        installMessageCapture()

        createTemplateViaUi(tenant, "Delete Source", "delete-source")

        gotoAndReady("/tenants/${tenant.id}/templates/default/delete-source/settings")
        page.locator("[data-testid='delete-action']").click()
        page.locator("[data-testid='confirm-dialog-confirm']").click()

        // A genuine full-page redirect (hx-boost="false") — wait for the list
        // page to actually load, then the flash-param handler in
        // embed-bridge.js fires on that fresh page's own load.
        assertThat(page.locator("h1")).containsText("Templates")

        val changed = latestMessageOfType("resource-changed")
        checkNotNull(changed) { "expected a 'resource-changed' message, got: ${capturedMessages()}" }
        val message = changed["message"] as Map<*, *>
        val resource = message["resource"] as Map<*, *>
        Assertions.assertThat(message["operation"]).isEqualTo("delete")
        Assertions.assertThat(resource["resourceType"]).isEqualTo("template")
        Assertions.assertThat(resource["tenantId"]).isEqualTo(tenant.id.value)
        Assertions.assertThat(resource["catalogKey"]).isEqualTo("default")
        Assertions.assertThat(resource["key"]).isEqualTo("delete-source")

        // The flash param must not survive in the address bar.
        assertThat(page).hasURL(Pattern.compile("^(?!.*resourceDeleted).*$"))
    }

    @Test
    fun `a host navigate message drives real navigation to the identified resource`() {
        val tenant = createTestTenant()
        installMessageCapture()

        createTemplateViaUi(tenant, "Navigate Target", "navigate-target")
        gotoAndReady("/tenants/${tenant.id}/templates")

        page.evaluate(
            """
            (resource) => {
                window.dispatchEvent(new MessageEvent('message', {
                    data: { source: 'epistola-host', type: 'navigate', resource: resource },
                    origin: 'https://embed-host.test',
                    source: window.parent,
                }));
            }
            """,
            mapOf(
                "resourceType" to "template",
                "tenantId" to tenant.id.value,
                "catalogKey" to "default",
                "key" to "navigate-target",
            ),
        )
        page.htmxSettle()

        assertThat(page.locator("#page-title-text")).containsText("Navigate Target")
        assertThat(page).hasURL(Pattern.compile(".*/templates/default/navigate-target$"))
    }
}
