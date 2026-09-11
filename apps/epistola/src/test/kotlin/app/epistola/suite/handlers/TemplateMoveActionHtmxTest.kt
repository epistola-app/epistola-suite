// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * The template settings tab offers the focused move dialog, which is how anyone would reach it
 * without first knowing the organise page exists.
 *
 * The link is built in Thymeleaf from the resource address, so its shape is easy to break silently:
 * a wrong separator or a missing catalog still renders a button, and only fails when clicked.
 */
class TemplateMoveActionHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedTemplate(name: String, relocation: Boolean): Pair<String, String> = withMediator {
        val tenant = createTenant(name)
        val key = TestIdHelpers.nextTemplateId()
        CreateDocumentTemplate(TemplateId(key, CatalogId.default(TenantId(tenant.id))), "Invoice").execute()
        if (relocation) SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_RELOCATION, enabled = true).execute()
        tenant.id.value to key.value
    }

    @Test
    fun `settings offers the move dialog for this template`() {
        val (tenantKey, templateKey) = seedTemplate("Template move on", relocation = true)

        val body = restTemplate.getForEntity(
            "/tenants/$tenantKey/templates/default/$templateKey/settings",
            String::class.java,
        ).body!!

        assertThat(body).contains("template-move-action")
        // The dialog is fetched for this template specifically, addressed as type:catalog/key.
        // Both separators are legal unencoded in a query value, so the link stays readable.
        assertThat(body).contains("/catalogs/organise/move?resource=template:default/$templateKey")
        // A mount is what opens the swapped-in <dialog>; without it the fetch would land invisibly.
        assertThat(body).contains("data-dialog-mount")
    }

    @Test
    fun `settings says nothing about moving when relocation is off`() {
        val (tenantKey, templateKey) = seedTemplate("Template move off", relocation = false)

        val body = restTemplate.getForEntity(
            "/tenants/$tenantKey/templates/default/$templateKey/settings",
            String::class.java,
        ).body!!

        assertThat(body).doesNotContain("template-move-action")
    }
}
