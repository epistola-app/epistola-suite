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
        assertThat(KnownFeatures.stageOf(KnownFeatures.STENCIL_PARAMETERS)).isEqualTo(KnownFeatures.FeatureStage.STABLE)
    }

    @Test
    fun `stable stage has no label so the UI renders no badge`() {
        assertThat(KnownFeatures.FeatureStage.STABLE.label).isNull()
        assertThat(KnownFeatures.FeatureStage.BETA.label).isEqualTo("Beta")
        assertThat(KnownFeatures.FeatureStage.ALPHA.label).isEqualTo("Alpha")
    }
}
