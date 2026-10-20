// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.EffectiveQualityStatus
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.commands.RecordManualFinding
import app.epistola.suite.quality.queries.GetQualityFinding
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * A `data-confirm-submit` button submits its form once confirmed the way the form says: an HTMX form
 * (`hx-post`) through HTMX, with its CSRF header and its swap, not as a native post that has neither.
 */
class ConfirmSubmitUiTest : BasePlaywrightTest() {

    @Test
    fun `resolving a finding after confirming goes through HTMX and swaps the finding`() {
        val tenant = createTenant("Confirm Submit")
        val (subject, key) = withMediator {
            SaveFeatureToggle(TenantKey.of(tenant.id.value), KnownFeatures.QUALITY, enabled = true).execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null).execute()
            val subject = QualitySubject.of(variantId)
            subject to RecordManualFinding(subject, "Wrong tone").execute()
        }

        gotoAndReady("/tenants/${tenant.id.value}/catalogs/default/quality/${key.value}")
        page.getByTestId("quality-resolve").click()
        page.getByTestId("confirm-dialog-confirm").click()

        assertThat(page.getByTestId("quality-resolve")).hasCount(0)
        Assertions.assertThat(withMediator { GetQualityFinding(subject.tenantKey, key).query() }!!.effectiveStatus)
            .isEqualTo(EffectiveQualityStatus.RESOLVED)
    }
}
