// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.templates.model.VariantSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The variants page's filter bar offers the attributes the template's variants use, each with the
 * values they use. It offered every value the attribute allows, so a template with an English and a
 * Dutch variant had a locale filter of 65 locales, 63 of which matched nothing.
 */
class VariantAttributeFilterTest {

    private val locales = listOf("en-US", "nl-NL", "de-DE", "fr-FR").map { AttributeOption(it, it) }
    private val locale = AttributeDescriptor("system.locale", "system", "locale", "Locale", locales)
    private val brand = AttributeDescriptor("acme.brand", "acme", "brand", "Brand", listOf(AttributeOption("a", "A")))

    private fun variant(key: String, attributes: Map<String, String>) = VariantSummary(VariantKey(key), key, attributes, isDefault = false, ready = false)

    @Test
    fun `the filter offers only the attributes and values the variants use`() {
        val variants = decorateVariants(
            listOf(variant("english", mapOf("system.locale" to "en-US")), variant("dutch", mapOf("locale" to "nl-NL")), variant("plain", emptyMap())),
            listOf(locale, brand),
        )

        val used = filterToUsedDescriptors(listOf(locale, brand), variants)

        assertThat(used.map { it.qualifiedKey }).containsExactly("system.locale")
        assertThat(used.single().options.map { it.code }).containsExactly("en-US", "nl-NL")
    }

    @Test
    fun `a value no longer allowed still shows, so the variant using it can be found`() {
        val variants = decorateVariants(listOf(variant("old", mapOf("system.locale" to "xx-XX"))), listOf(locale))

        assertThat(filterToUsedDescriptors(listOf(locale), variants).single().options.map { it.code }).containsExactly("xx-XX")
    }
}
