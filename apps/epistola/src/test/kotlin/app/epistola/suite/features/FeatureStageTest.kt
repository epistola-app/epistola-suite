// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.features

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the [KnownFeatures.FeatureStage] enum against the design-system CSS: every non-stable stage
 * declares a label + badge class, and that class actually has a `.badge-*` rule in
 * `components.css` — so a newly added stage can never ship as an unstyled badge. The CSS is read from
 * the build-copied classpath resource Spring serves at `/design-system/components.css`.
 */
class FeatureStageTest {
    private val componentsCss: String by lazy {
        FeatureStageTest::class.java.getResource("/static/design-system/components.css")
            ?.readText()
            ?: error("components.css not found on the classpath — is the design-system copy task wired into processResources?")
    }

    @Test
    fun `stable stage carries no label or badge class`() {
        assertThat(KnownFeatures.FeatureStage.STABLE.label).isNull()
        assertThat(KnownFeatures.FeatureStage.STABLE.badgeClass).isNull()
    }

    @Test
    fun `every non-stable stage has a label, a badge class, and a matching CSS rule`() {
        val nonStable = KnownFeatures.FeatureStage.entries.filter { it != KnownFeatures.FeatureStage.STABLE }
        assertThat(nonStable).isNotEmpty

        assertThat(nonStable).allSatisfy { stage ->
            assertThat(stage.label).withFailMessage("%s must have a label", stage).isNotNull
            assertThat(stage.badgeClass).withFailMessage("%s must have a badge class", stage).isNotNull
            assertThat(componentsCss)
                .withFailMessage("components.css is missing a `.%s` rule for stage %s", stage.badgeClass, stage)
                .contains(".${stage.badgeClass} {")
        }
    }
}
