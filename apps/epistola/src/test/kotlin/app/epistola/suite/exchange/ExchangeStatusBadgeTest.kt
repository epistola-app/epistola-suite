// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Holds the Exchange status enums and the design-system CSS together, the same way
 * `FeatureStageTest` does for feature stages: a status whose badge class has no `.badge-*` rule
 * would render as unstyled text — and these are failure states, so it is exactly the kind of thing
 * nobody sees until a customer hits it.
 */
class ExchangeStatusBadgeTest {
    private val componentsCss: String by lazy {
        ExchangeStatusBadgeTest::class.java.getResource("/static/design-system/components.css")
            ?.readText()
            ?: error("components.css not found on the classpath — is the design-system copy task wired into processResources?")
    }

    @Test
    fun `every connection status has a label and a styled badge`() {
        assertThat(ExchangeConnectionStatus.entries).allSatisfy { status ->
            assertThat(status.label).withFailMessage("%s must have a label", status).isNotBlank()
            assertThat(componentsCss)
                .withFailMessage("components.css is missing a `.%s` rule for %s", status.badgeClass, status)
                .contains(".${status.badgeClass} {")
        }
    }

    @Test
    fun `every publication status has a label and a styled badge`() {
        assertThat(CatalogPublicationStatus.entries).allSatisfy { status ->
            assertThat(status.label).withFailMessage("%s must have a label", status).isNotBlank()
            assertThat(componentsCss)
                .withFailMessage("components.css is missing a `.%s` rule for %s", status.badgeClass, status)
                .contains(".${status.badgeClass} {")
        }
    }
}
