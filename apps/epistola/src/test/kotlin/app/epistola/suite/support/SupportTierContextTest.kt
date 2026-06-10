package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.feedback.sync.FeedbackSyncPort
import app.epistola.suite.snapshots.SnapshotSyncPort
import app.epistola.suite.upgrading.CompatibilitySyncPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * Boots the full app with the **support tier enabled** and asserts the context loads with exactly
 * one bean per sync port — the hub adapters, not the no-op fallbacks. Regression guard for the
 * `@ConditionalOnMissingBean` ordering bug that registered both a no-op and a hub adapter and failed
 * startup with "a single bean, but 2 were found". Registration is fire-and-forget, so no running hub
 * is needed for the context to come up.
 */
@TestPropertySource(
    properties = [
        "epistola.support.enabled=true",
        "epistola.installation.company-name=Test Co",
        "epistola.installation.admin-email=test@example.com",
        "epistola.installation.environment=test",
        "epistola.support.hub.host=localhost",
        "epistola.support.hub.port=9090",
        "epistola.support.hub.plaintext=true",
    ],
)
class SupportTierContextTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var snapshotSyncPort: SnapshotSyncPort

    @Autowired
    private lateinit var compatibilitySyncPort: CompatibilitySyncPort

    @Autowired
    private lateinit var feedbackSyncPort: FeedbackSyncPort

    @Test
    fun `support tier wires the hub adapters with no bean conflicts`() {
        // Single-bean injection above already proves no no-op/hub-adapter collision; isEnabled()
        // confirms the hub adapter won (the no-op reports false).
        assertThat(snapshotSyncPort.isEnabled()).isTrue()
        assertThat(compatibilitySyncPort.isEnabled()).isTrue()
        assertThat(feedbackSyncPort.isEnabled()).isTrue()
    }
}
