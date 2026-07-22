package app.epistola.suite.features

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KnownFeaturesTest {
    @Test
    fun `every known feature has display metadata`() {
        assertThat(KnownFeatures.all).allSatisfy { key ->
            val md = KnownFeatures.metadataFor(key)
            assertThat(md).withFailMessage("missing metadata for %s", key.value).isNotNull
            assertThat(md!!.title).isNotBlank
            assertThat(md.description).isNotBlank
        }
    }

    @Test
    fun `backups is marked beta and other features are stable by default`() {
        assertThat(KnownFeatures.stageOf(KnownFeatures.SUPPORT_BACKUPS)).isEqualTo(KnownFeatures.FeatureStage.BETA)
        assertThat(KnownFeatures.stageOf(KnownFeatures.SUPPORT_FEEDBACK)).isEqualTo(KnownFeatures.FeatureStage.STABLE)
    }

    /**
     * Quality is not a support-tier feature and must never become one by accident. A key in
     * [KnownFeatures.SUPPORT_TIER] is gated on a hub entitlement, and the hub wire contract has no
     * `QUALITY` feature to grant — so a quality key there would make the feature *permanently
     * unavailable* on every installation running with the support tier on, silently. Membership of
     * [KnownFeatures.HUB_ONLY] would likewise default it off whenever the tier is off, when in fact
     * the ledger and its in-process sources work perfectly well without a hub.
     *
     * `WireContractAlignmentTest` does not catch this — it asserts three named keys rather than
     * iterating [KnownFeatures.all]. This is the guard.
     */
    @Test
    fun `quality is toggle-only and never hub-gated`() {
        assertThat(KnownFeatures.SUPPORT_TIER).doesNotContain(KnownFeatures.QUALITY)
        assertThat(KnownFeatures.HUB_ONLY).doesNotContain(KnownFeatures.QUALITY)
    }

    @Test
    fun `ai chat is toggle-only and never hub-gated`() {
        assertThat(KnownFeatures.SUPPORT_TIER).doesNotContain(KnownFeatures.AI_CHAT)
        assertThat(KnownFeatures.HUB_ONLY).doesNotContain(KnownFeatures.AI_CHAT)
    }

    /**
     * Quality is alpha: the ledger's semantics are settled but its surfaces are not, and the
     * badge is how a tenant admin is told that on the Features page before switching it on.
     */
    @Test
    fun `quality is marked alpha`() {
        assertThat(KnownFeatures.stageOf(KnownFeatures.QUALITY)).isEqualTo(KnownFeatures.FeatureStage.ALPHA)
    }

    @Test
    fun `ai chat is marked alpha`() {
        assertThat(KnownFeatures.stageOf(KnownFeatures.AI_CHAT)).isEqualTo(KnownFeatures.FeatureStage.ALPHA)
    }

    /**
     * The `quality` default is stated explicitly in [FeatureDefaults.isEnabled] rather than falling
     * through to its `else -> false`. Both yield "off" today, so this pins the intent: the branch is
     * what makes flipping the default a deliberate one-line edit.
     */
    @Test
    fun `quality default is off and resolved by its own branch`() {
        assertThat(FeatureDefaults().isEnabled(KnownFeatures.QUALITY)).isFalse()
        assertThat(FeatureDefaults(quality = true).isEnabled(KnownFeatures.QUALITY)).isTrue()
    }

    @Test
    fun `ai chat default is off and resolved by its own branch`() {
        assertThat(FeatureDefaults().isEnabled(KnownFeatures.AI_CHAT)).isFalse()
        assertThat(FeatureDefaults(aiChat = true).isEnabled(KnownFeatures.AI_CHAT)).isTrue()
    }

    @Test
    fun `stable stage has no label so the UI renders no badge`() {
        assertThat(KnownFeatures.FeatureStage.STABLE.label).isNull()
        assertThat(KnownFeatures.FeatureStage.BETA.label).isEqualTo("Beta")
        assertThat(KnownFeatures.FeatureStage.ALPHA.label).isEqualTo("Alpha")
    }
}
