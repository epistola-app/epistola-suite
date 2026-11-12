// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common.ids

import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

/**
 * One rule for what a new resource key may be — the contract's format, no reserved word — held where
 * an author chooses a key and never where one is read ([NewResourceKeys]).
 */
class NewResourceKeysTest {

    private val tenant = TenantId(TenantKey.of("acme"))
    private val catalog = CatalogId("acme", CatalogKey.of("letters"), tenant)
    private val template = TemplateId(TemplateKey.of("letter"), catalog)

    /** Every command that lets an author choose a resource key, by the kind its refusal names. */
    private val creates: Map<String, (String) -> Any> = mapOf(
        "Template" to { key -> CreateDocumentTemplate(TemplateId(TemplateKey.of(key), catalog), "Letter", catalogVersion = null) },
        "Variant" to { key -> CreateVariant(VariantId(VariantKey.of(key), template), "Dutch", description = null, catalogVersion = null) },
        "Stencil" to { key -> CreateStencil(StencilId(StencilKey.of(key), catalog), "Header", catalogVersion = null) },
        "Theme" to { key -> CreateTheme(ThemeId(ThemeKey.of(key), catalog), "Corporate", catalogVersion = null) },
        "Attribute" to { key -> CreateAttributeDefinition(AttributeId(AttributeKey.of(key), catalog), "Language", catalogVersion = null) },
        "Code list" to { key ->
            CreateCodeList(
                CodeListId(CodeListKey.of(key), catalog),
                displayName = "Countries",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry(code = "nl", label = "Netherlands")),
                catalogVersion = null,
            )
        },
        "Image" to { key -> UploadAsset(catalog, "Logo", AssetMediaType("image/png"), ByteArray(0), null, null, AssetKey.of(key), catalogVersion = null) },
        "Environment" to { key -> CreateEnvironment(EnvironmentId(EnvironmentKey.of(key), tenant), "Test") },
    )

    @Test
    fun `no resource can be created with a reserved word as its key`() {
        assertSoftly { softly ->
            for ((kind, create) in creates) {
                for (word in NewResourceKeys.RESERVED_WORDS) {
                    softly.assertThatThrownBy { create(word) }
                        .`as`("$kind keyed $word")
                        .isInstanceOfSatisfying(ValidationException::class.java) {
                            assertThat(it.field).isEqualTo("key")
                            assertThat(it.code).isEqualTo(ValidationCode.KEY_RESERVED)
                        }
                        .hasMessage("$kind ID '$word' is reserved and cannot be used")
                }
            }
        }
    }

    @Test
    fun `a key that only contains a reserved word is fine`() {
        for ((kind, create) in creates - "Image") {
            assertThat(create("newsletter")).`as`(kind).isNotNull()
        }
    }

    @Test
    fun `a key stored as a reserved word still reads`() {
        // Themes, code lists, attributes and images accepted these words before; a catalog import may bring any.
        for (word in NewResourceKeys.RESERVED_WORDS) {
            listOf(TemplateKey.of(word), VariantKey.of(word), StencilKey.of(word), ThemeKey.of(word), AttributeKey.of(word), CodeListKey.of(word), AssetKey.of(word), FontKey.of(word), EnvironmentKey.of(word))
                .forEach { assertThat(it.value).isEqualTo(word) }
        }
    }

    @Test
    fun `a new key may start with a digit, as the contract allows`() {
        for ((kind, create) in creates - "Image" - "Environment") {
            assertThat(create("2024-letter")).`as`(kind).isNotNull()
        }
    }

    @Test
    fun `a new key the contract refuses is refused when chosen, not when its catalog is released`() {
        val tooLong = "a" + "b".repeat(CatalogKeys.MAX_LENGTH)
        assertSoftly { softly ->
            for ((kind, key) in listOf("Code list" to tooLong, "Image" to "ab")) {
                softly.assertThatThrownBy { creates.getValue(kind)(key) }
                    .`as`("$kind keyed $key")
                    .isInstanceOfSatisfying(ValidationException::class.java) {
                        assertThat(it.field).isEqualTo("key")
                        assertThat(it.code).isEqualTo(ValidationCode.KEY_FORMAT_INVALID)
                    }
                    .hasMessage("$kind ID must be 3 to 50 characters: lowercase letters and digits in hyphen-separated parts")
            }
        }
    }

    @Test
    fun `every key the contract allows, and every key stored before, still reads`() {
        // A leading digit, as the contract allows and a catalog import may bring.
        listOf(TemplateKey.of("2024-letter"), VariantKey.of("2024-letter"), StencilKey.of("2024-letter"), ThemeKey.of("2024-letter"), AttributeKey.of("2024-letter"), CodeListKey.of("2024-letter"), AssetKey.of("2024-letter"), FontKey.of("2024-letter"))
            .forEach { assertThat(it.value).isEqualTo("2024-letter") }
        // Lengths the suite allowed before the contract's rule: kept readable.
        assertThat(CodeListKey.of("a" + "b".repeat(63)).value).hasSize(64)
        assertThat(FontKey.of("ab").value).isEqualTo("ab")
    }
}
