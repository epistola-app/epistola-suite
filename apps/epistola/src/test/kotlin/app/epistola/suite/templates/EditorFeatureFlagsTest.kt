// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EditorFeatureFlagsTest {
    /**
     * The editor bundle reads feature state by the field names of its `EditorFeatures`
     * interface (modules/editor); the backend forwards toggles under those names via
     * [EDITOR_FEATURES]. Nothing can check the cross-language pair at compile time, so
     * both sides follow one mechanical convention — the TS field name is the
     * lowerCamelCase form of the feature key — and this test pins the Kotlin side to
     * it. A typo like `editorWalkThrough` fails here instead of silently rendering
     * the feature disabled for everyone.
     */
    @Test
    fun `editor feature names are the lowerCamelCase form of their feature key`() {
        assertThat(EDITOR_FEATURES).isNotEmpty
        for ((name, feature) in EDITOR_FEATURES) {
            val expected = feature.value
                .split('-')
                .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercase) }
                .joinToString("")
            assertThat(name)
                .withFailMessage(
                    "EDITOR_FEATURES maps \"%s\" to %s, expected the name \"%s\" " +
                        "(lowerCamelCase of the feature key — the convention the editor's " +
                        "EditorFeatures field names follow)",
                    name,
                    feature.value,
                    expected,
                ).isEqualTo(expected)
        }
    }
}
