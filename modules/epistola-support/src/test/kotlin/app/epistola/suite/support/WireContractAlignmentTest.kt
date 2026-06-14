package app.epistola.suite.support

import app.epistola.hub.contract.SupportFeature
import app.epistola.suite.features.KnownFeatures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Core's [KnownFeatures] is hub-free and so keeps its own feature-key strings rather than importing
 * the hub wire contract. This guards against the two drifting: the suite-facing keys must equal the
 * shared [SupportFeature] wire keys (the hub grants and the suite reads back). If a key is renamed on
 * one side only, entitlements would silently stop matching — this test fails first.
 */
class WireContractAlignmentTest {
    @Test
    fun `core feature keys match the shared hub wire contract`() {
        assertThat(KnownFeatures.SUPPORT_FEEDBACK.value).isEqualTo(SupportFeature.FEEDBACK.wireKey)
        assertThat(KnownFeatures.SUPPORT_BACKUPS.value).isEqualTo(SupportFeature.BACKUPS.wireKey)
        assertThat(KnownFeatures.SUPPORT_UPGRADING.value).isEqualTo(SupportFeature.UPGRADING.wireKey)
    }
}
