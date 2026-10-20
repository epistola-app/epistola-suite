// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.sources

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.QualitySubjectType
import app.epistola.suite.quality.ResolvedTemplateDependencies
import app.epistola.suite.quality.ThemePresets
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRefInherit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThemePresetQualitySourceTest {
    private val source = ThemePresetQualitySource()

    private val subject = QualitySubject(
        type = QualitySubjectType.VARIANT,
        urn = "urn:epistola:variant:acme/default/invoice/v1",
        ignoreScopeUrn = "urn:epistola:template:acme/default/invoice",
        tenantKey = TenantKey.of("acme"),
        catalogKey = CatalogKey.of("default"),
        templateKey = TemplateKey.of("invoice"),
        variantKey = "v1",
    )

    private fun input(theme: ThemePresets?, vararg presets: Pair<String, String?>) = QualityCheckInput(
        subject = subject,
        templateModel = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = emptyList())) +
                presets.associate { (id, preset) -> id to Node(id = id, type = "text", slots = emptyList(), stylePreset = preset) },
            slots = emptyMap(),
            themeRef = ThemeRefInherit(),
        ),
        dataExamples = emptyList(),
        dataModel = null,
        dependencies = ResolvedTemplateDependencies(themePresets = theme),
    )

    @Test
    fun `a block whose preset the theme does not style is reported on that block`() {
        val findings = source.check(input(ThemePresets("Brand", setOf("muted")), "a" to "muted", "b" to "accent", "c" to null))

        assertThat(findings).singleElement().satisfies({ finding ->
            assertThat(finding.nodeIds).containsExactly("b")
            assertThat(finding.severity).isEqualTo(QualitySeverity.WARNING)
            assertThat(finding.message).contains("\"accent\"").contains("\"Brand\"")
        })
    }

    @Test
    fun `without a theme every preset is reported, and without resolution nothing is`() {
        assertThat(source.check(input(ThemePresets(null, emptySet()), "a" to "muted")).single().message).contains("no theme applies")
        assertThat(source.check(input(null, "a" to "muted"))).isEmpty()
    }
}
