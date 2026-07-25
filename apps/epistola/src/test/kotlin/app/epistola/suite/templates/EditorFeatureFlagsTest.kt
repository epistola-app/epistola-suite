package app.epistola.suite.templates

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EditorFeatureFlagsTest {
    /**
     * The editor bundle reads flags by the field names of its `EditorFeatureFlags`
     * interface (modules/editor); the backend forwards toggles under those names via
     * [EDITOR_FEATURE_FLAGS]. Nothing can check the cross-language pair at compile
     * time, so both sides follow one mechanical convention — the TS field name is the
     * lowerCamelCase form of the feature key — and this test pins the Kotlin side to
     * it. A typo like `editorWalkThrough` fails here instead of silently rendering
     * the feature disabled for everyone.
     */
    @Test
    fun `editor flag names are the lowerCamelCase form of their feature key`() {
        assertThat(EDITOR_FEATURE_FLAGS).isNotEmpty
        for ((feature, name) in EDITOR_FEATURE_FLAGS) {
            val expected = feature.value
                .split('-')
                .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercase) }
                .joinToString("")
            assertThat(name)
                .withFailMessage(
                    "EDITOR_FEATURE_FLAGS maps %s to \"%s\", expected \"%s\" " +
                        "(lowerCamelCase of the feature key — the convention the editor's " +
                        "EditorFeatureFlags field names follow)",
                    feature.value,
                    name,
                    expected,
                ).isEqualTo(expected)
        }
    }
}
