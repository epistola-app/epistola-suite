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
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRefInherit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ComponentLimitQualitySourceTest {
    private val source = ComponentLimitQualitySource()

    private val subject = QualitySubject(
        type = QualitySubjectType.VARIANT,
        urn = "urn:epistola:variant:acme/default/letter/v1",
        ignoreScopeUrn = "urn:epistola:template:acme/default/letter",
        tenantKey = TenantKey.of("acme"),
        catalogKey = CatalogKey.of("default"),
        templateKey = TemplateKey.of("letter"),
        variantKey = "v1",
    )

    private fun node(id: String, type: String) = Node(id = id, type = type, slots = emptyList(), props = emptyMap<String, Any?>())

    private fun inputFor(vararg nodes: Node) = QualityCheckInput(
        subject = subject,
        templateModel = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = (listOf(node("root", "root")) + nodes).associateBy { it.id },
            slots = emptyMap(),
            themeRef = ThemeRefInherit(),
        ),
        dataExamples = emptyList(),
        dataModel = null,
    )

    @Test
    fun `the limits come from the contract's component registry`() {
        assertThat(ComponentLimitQualitySource.MAX_INSTANCES_PER_DOCUMENT).containsEntry("addressblock", 1)
    }

    @Test
    fun `a second address block, for instance from an included stencil, is reported on every instance`() {
        val findings = source.check(inputFor(node("address-own", "addressblock"), node("address-from-stencil", "addressblock")))

        assertThat(findings).singleElement().satisfies({
            assertThat(it.ruleId).isEqualTo(ComponentLimitQualitySource.RULE_TOO_MANY_INSTANCES)
            assertThat(it.messageCode).isEqualTo(ComponentLimitQualitySource.MSG_TOO_MANY_INSTANCES)
            assertThat(it.severity).isEqualTo(QualitySeverity.WARNING)
            assertThat(it.nodeIds).containsExactly("address-from-stencil", "address-own")
            assertThat(it.context.get("componentType").stringValue()).isEqualTo("addressblock")
            assertThat(it.context.get("limit").intValue()).isEqualTo(1)
            assertThat(it.context.get("count").intValue()).isEqualTo(2)
        })
    }

    @Test
    fun `one address block is not reported`() {
        assertThat(source.check(inputFor(node("address", "addressblock")))).isEmpty()
    }

    @Test
    fun `components without a limit are not reported, however many there are`() {
        assertThat(source.check(inputFor(node("footer-1", "pagefooter"), node("footer-2", "pagefooter")))).isEmpty()
    }

    @Test
    fun `the fingerprint is stable across re-runs and changes with the count`() {
        val two = inputFor(node("a1", "addressblock"), node("a2", "addressblock"))
        val three = inputFor(node("a1", "addressblock"), node("a2", "addressblock"), node("a3", "addressblock"))

        assertThat(source.check(two).single().fingerprint).isEqualTo(source.check(two).single().fingerprint)
        assertThat(source.check(three).single().fingerprint).isNotEqualTo(source.check(two).single().fingerprint)
    }
}
