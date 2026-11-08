// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.feedback.sync.FeedbackSyncPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * Boots the full app with the **support connection enabled** against a support service that does not
 * exist: the context starts (nothing calls the service at startup), and feedback has one sync port,
 * the no-op one, since nothing sends feedback anywhere.
 */
@TestPropertySource(
    properties = [
        "epistola.support.enabled=true",
        "epistola.installation.company-name=Test Co",
        "epistola.installation.admin-email=test@example.com",
        "epistola.installation.environment=test",
        "epistola.support.endpoint=https://support.invalid",
    ],
)
class SupportTierContextTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var feedbackSyncPort: FeedbackSyncPort

    @Test
    fun `the support connection starts without the service, and feedback stays in the installation`() {
        assertThat(feedbackSyncPort.isEnabled()).isFalse()
    }
}
