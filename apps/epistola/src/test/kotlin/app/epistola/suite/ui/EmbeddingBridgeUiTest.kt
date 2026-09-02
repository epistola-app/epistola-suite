// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.tenants.Tenant
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.node.JsonNodeFactory
import java.util.regex.Pattern

/**
 * End-to-end coverage for `embed-bridge.js` (docs/embedding.md, ADR 0015) —
 * the demo-mode-only postMessage bridge. Runs in its own Spring context
 * (embedding turned on) so every other Playwright test class keeps embedding
 * off via [BasePlaywrightTest]'s default.
 *
 * `resource-mutated` detection is deliberately client-side only (no Kotlin
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

        val event = latestMessageOfType("event")
        checkNotNull(event) { "expected a 'templates-opened' event, got: ${capturedMessages()}" }
        Assertions.assertThat((event["message"] as Map<*, *>)["event"]).isEqualTo("templates-opened")
    }

    @Test
    fun `creating a template through the UI fires a resource-mutated create message, then a navigated message`() {
        val tenant = createTestTenant()
        installMessageCapture()

        createTemplateViaUi(tenant, "Embed Bridge Template", "embed-bridge-template")
        assertThat(page.locator("#page-title-text")).containsText("Embed Bridge Template")

        val changed = latestMessageOfType("resource-mutated")
        checkNotNull(changed) { "expected a 'resource-mutated' message, got: ${capturedMessages()}" }
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
    fun `renaming a template's settings fires a resource-mutated update message`() {
        val tenant = createTestTenant()
        installMessageCapture()

        createTemplateViaUi(tenant, "Rename Source", "rename-source")

        gotoAndReady("/tenants/${tenant.id}/templates/default/rename-source/settings")
        val input = page.locator("[data-template-name-input]")
        input.fill("Renamed Via Embed Test")
        input.press("Enter")
        page.htmxSettle()

        val changed = latestMessageOfType("resource-mutated")
        checkNotNull(changed) { "expected a 'resource-mutated' message, got: ${capturedMessages()}" }
        val message = changed["message"] as Map<*, *>
        val resource = message["resource"] as Map<*, *>
        Assertions.assertThat(message["operation"]).isEqualTo("update")
        Assertions.assertThat(resource["resourceType"]).isEqualTo("template")
        Assertions.assertThat(resource["catalogKey"]).isEqualTo("default")
        Assertions.assertThat(resource["key"]).isEqualTo("rename-source")
    }

    @Test
    fun `deleting a template fires a resource-mutated delete message via the flash query param`() {
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

        val changed = latestMessageOfType("resource-mutated")
        checkNotNull(changed) { "expected a 'resource-mutated' message, got: ${capturedMessages()}" }
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
                    data: { source: 'epistola-host', type: 'navigate', target: { view: 'detail', resource: resource } },
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

    @Test
    fun `a resource-less templates navigate message stays in the current tenant`() {
        val tenant = createTestTenant()
        installMessageCapture()

        gotoAndReady("/tenants/${tenant.id}/templates/new")

        page.evaluate(
            """
            () => {
                window.dispatchEvent(new MessageEvent('message', {
                    data: { source: 'epistola-host', type: 'navigate', target: { view: 'templates' } },
                    origin: 'https://embed-host.test',
                    source: window.parent,
                }));
            }
            """,
        )
        page.htmxSettle()

        assertThat(page.locator("h1")).containsText("Templates")
        assertThat(page).hasURL(Pattern.compile(".*/tenants/${tenant.id}/templates$"))
    }

    @Test
    fun `publishing a template reports mutation and a successful PDF preview reports its trusted event`() {
        val tenant = createTestTenant()
        installMessageCapture()
        createTemplateViaUi(tenant, "Publish Training Template", "publish-training-template")

        page.locator("[title='Publish draft']").click()
        page.htmxSettle()

        val published = latestMessageOfType("resource-mutated")
        checkNotNull(published) { "expected a publish mutation, got: ${capturedMessages()}" }
        Assertions.assertThat((published["message"] as Map<*, *>)["operation"]).isEqualTo("publish")

        page.locator("[data-pdf-preview]").click()
        page.waitForFunction(
            """() => window.__epistolaMessages.some(({ message }) => message?.type === 'event' && message.event === 'pdf-previewed')""",
        )
    }

    @Test
    fun `a host navigate message can open a template data contract`() {
        val tenant = createTestTenant()
        installMessageCapture()
        createTemplateViaUi(tenant, "Data Contract Target", "data-contract-target")
        gotoAndReady("/tenants/${tenant.id}/templates")

        page.evaluate(
            """
            (resource) => {
                window.dispatchEvent(new MessageEvent('message', {
                    data: { source: 'epistola-host', type: 'navigate', target: { view: 'data-contract', resource: resource } },
                    origin: 'https://embed-host.test',
                    source: window.parent,
                }));
            }
            """,
            mapOf(
                "resourceType" to "template",
                "tenantId" to tenant.id.value,
                "catalogKey" to "default",
                "key" to "data-contract-target",
            ),
        )
        page.htmxSettle()

        assertThat(page).hasURL(Pattern.compile(".*/templates/default/data-contract-target/data-contract$"))
    }

    @Test
    fun `a host navigate message resolves a template editor to its default variant`() {
        val tenant = createTestTenant()
        installMessageCapture()
        createTemplateViaUi(tenant, "Editor Target", "editor-target")
        gotoAndReady("/tenants/${tenant.id}/templates")

        page.evaluate(
            """
            (resource) => {
                window.dispatchEvent(new MessageEvent('message', {
                    data: { source: 'epistola-host', type: 'navigate', target: { view: 'editor', resource: resource } },
                    origin: 'https://embed-host.test',
                    source: window.parent,
                }));
            }
            """,
            mapOf(
                "resourceType" to "template",
                "tenantId" to tenant.id.value,
                "catalogKey" to "default",
                "key" to "editor-target",
            ),
        )
        page.waitForSelector("epistola-editor")

        assertThat(page).hasURL(Pattern.compile(".*/templates/default/editor-target/variants/[^/]+/editor$"))
        assertThat(page.locator("#epistola-embed-config")).hasCount(1)
        Assertions.assertThat(page.evaluate("() => typeof window.epistolaEmbedBridge")).isEqualTo("object")
    }

    @Test
    fun `assessment returns one satisfied result for an existing template`() {
        val tenant = createTestTenant()
        installMessageCapture()
        createTemplateViaUi(tenant, "Inspectable Template", "inspectable-template")

        page.evaluate(
            """
            (resource) => {
                window.dispatchEvent(new MessageEvent('message', {
                    data: {
                        source: 'epistola-host',
                        type: 'assess',
                        requestId: 'existing-template',
                        resources: [resource],
                        predicates: [{ type: 'resource-exists', resource: 'training-template' }],
                    },
                    origin: 'https://embed-host.test',
                    source: window.parent,
                }));
            }
            """,
            mapOf(
                "id" to "training-template",
                "resourceType" to "template",
                "tenantId" to tenant.id.value,
                "catalogKey" to "default",
                "key" to "inspectable-template",
            ),
        )
        page.waitForFunction(
            """() => window.__epistolaMessages.some(({ message }) => message?.type === 'assessment-result' && message.requestId === 'existing-template')""",
        )

        val message = latestMessageOfType("assessment-result")?.get("message") as? Map<*, *>
        checkNotNull(message) { "expected an assessment result, got: ${capturedMessages()}" }
        val results = message["results"] as List<*>
        Assertions.assertThat((results.single() as Map<*, *>)["status"]).isEqualTo("satisfied")
    }

    @Test
    fun `Lesson 2 predicate assessment reflects field and heading changes after reload`() {
        val tenant = createTestTenant()
        val slug = "lesson-two-template"
        installMessageCapture()
        createTemplateViaUi(tenant, "Lesson Two Template", slug)
        val templateId = TemplateId(TemplateKey.of(slug), CatalogId.default(TenantId(tenant.id)))

        assertLessonTwoAssessment(tenant, slug, listOf("satisfied", "unsatisfied", "unsatisfied"))
        withMediator { setRecipientField(templateId, true) }
        assertLessonTwoAssessment(tenant, slug, listOf("satisfied", "satisfied", "unsatisfied"))
        withMediator { setHeadingExpression(templateId, true) }
        assertLessonTwoAssessment(tenant, slug, listOf("satisfied", "satisfied", "satisfied"))
        withMediator { setHeadingExpression(templateId, false) }
        assertLessonTwoAssessment(tenant, slug, listOf("satisfied", "satisfied", "unsatisfied"))
        withMediator { setHeadingExpression(templateId, true) }
        assertLessonTwoAssessment(tenant, slug, listOf("satisfied", "satisfied", "satisfied"))
        withMediator { setRecipientField(templateId, false) }
        assertLessonTwoAssessment(tenant, slug, listOf("satisfied", "unsatisfied", "satisfied"))
        withMediator { setRecipientField(templateId, true) }
        assertLessonTwoAssessment(tenant, slug, listOf("satisfied", "satisfied", "satisfied"))
    }

    private fun assertLessonTwoAssessment(tenant: Tenant, slug: String, expected: List<String>) {
        gotoAndReady("/tenants/${tenant.id}/templates/default/$slug")
        val requestId = "lesson-two-${System.nanoTime()}"
        val resource = mapOf("id" to "training-template", "resourceType" to "template", "tenantId" to tenant.id.value, "catalogKey" to "default", "key" to slug)
        page.evaluate(
            """
            ({ requestId, resource }) => window.dispatchEvent(new MessageEvent('message', {
                data: { source: 'epistola-host', type: 'assess', requestId, resources: [resource], predicates: [
                    { type: 'resource-exists', resource: 'training-template' },
                    { type: 'data-contract-property', resource: 'training-template', path: 'recipientName', required: true },
                    { type: 'default-variant-heading-expression', resource: 'training-template', path: 'recipientName' },
                ] }, origin: 'https://embed-host.test', source: window.parent,
            }))
            """,
            mapOf("requestId" to requestId, "resource" to resource),
        )
        page.waitForFunction(
            """(id) => window.__epistolaMessages.some(({ message }) => message?.type === 'assessment-result' && message.requestId === id)""",
            requestId,
        )
        val message = latestMessageOfType("assessment-result")?.get("message") as? Map<*, *>
        checkNotNull(message) { "expected an assessment result, got: ${capturedMessages()}" }
        val statuses = (message["results"] as List<*>).map { (it as Map<*, *>)["status"] }
        Assertions.assertThat(statuses).containsExactlyElementsOf(expected)
    }

    private fun setRecipientField(templateId: TemplateId, present: Boolean) {
        CreateContractVersion(templateId).execute()
        val schema = JsonNodeFactory.instance.objectNode().apply {
            put("type", "object")
            set(
                "properties",
                JsonNodeFactory.instance.objectNode().apply {
                    if (present) set("recipientName", JsonNodeFactory.instance.objectNode().put("type", "string"))
                },
            )
            set("required", JsonNodeFactory.instance.arrayNode().apply { if (present) add("recipientName") })
        }
        UpdateContractVersion(templateId = templateId, dataModel = schema).execute()
    }

    private fun setHeadingExpression(templateId: TemplateId, present: Boolean) {
        val variant = GetVariantSummaries(templateId).query().first { it.isDefault }
        val content = mapOf("type" to "doc", "content" to listOf(mapOf("type" to "heading", "attrs" to mapOf("level" to 1), "content" to if (present) listOf(mapOf("type" to "expression", "attrs" to mapOf("expression" to "recipientName"))) else emptyList<Any>())))
        UpdateDraft(
            variantId = VariantId(variant.id, templateId),
            templateModel = TemplateDocument(
                modelVersion = 1,
                root = "root",
                nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot")), "heading" to Node(id = "heading", type = "text", slots = emptyList(), props = mapOf("content" to content))),
                slots = mapOf("root-slot" to Slot("root-slot", "root", "children", listOf("heading"))),
                themeRef = ThemeRef.Inherit,
            ),
        ).execute()
    }
}
