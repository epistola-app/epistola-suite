// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.options.FilePayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader

/** Browser coverage for canonicalizing OS-provided font MIME types before upload. */
class FontUploadMimeUiTest : BasePlaywrightTest() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Test
    fun `corporate browser media type is normalized before font upload`() {
        lateinit var tenant: Tenant
        withMediator {
            tenant = CreateTenant(
                id = TenantKey.of("test-font-mime-${System.nanoTime()}"),
                name = "Font MIME UI Test",
            ).execute()
        }

        gotoAndReady("/tenants/${tenant.id}/fonts/new")
        page.locator("#slug").fill("corporate-sans")
        page.locator("#name").fill("Corporate Sans")

        val bytes = resourceLoader
            .getResource("classpath:epistola/fonts/inter/inter-Regular.ttf")
            .contentAsByteArray
        val fileInput = page.locator("input[data-font-file]")
        fileInput.setInputFiles(
            FilePayload("corporate-sans.ttf", "application/x-octect-stream", bytes),
        )

        assertThat(fileInput.evaluate("el => el.files[0].type"))
            .isEqualTo("font/ttf")

        page.locator("[data-testid='create-form-submit']").click()
        page.htmxSettle()

        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#font-grid-items"))
            .containsText("Corporate Sans")
    }
}
