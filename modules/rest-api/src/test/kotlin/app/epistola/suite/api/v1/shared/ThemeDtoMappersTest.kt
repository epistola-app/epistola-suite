// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.BlockStylePresetDto
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ThemeDtoMappersTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `converts block style preset values without losing their types`() {
        val styles =
            objectMapper.readValue(
                """
                {
                  "fontSize": "12pt",
                  "keepTogether": true,
                  "opacity": 0.5,
                  "shadow": {
                    "color": "#000000"
                  }
                }
                """.trimIndent(),
                ObjectNode::class.java,
            )
        val presets =
            mapOf(
                "notice" to
                    BlockStylePresetDto(
                        label = "Notice",
                        styles = styles,
                        applicableTo = listOf("paragraph"),
                    ),
            )

        val converted = assertNotNull(presets.toDomainPresets(objectMapper))
        val preset = assertNotNull(converted["notice"])

        assertEquals("Notice", preset.label)
        assertEquals(listOf("paragraph"), preset.applicableTo)
        assertEquals("12pt", preset.styles["fontSize"])
        assertEquals(true, preset.styles["keepTogether"])
        assertEquals(0.5, (preset.styles["opacity"] as Number).toDouble())
        val shadow = assertNotNull(preset.styles["shadow"] as? Map<*, *>)
        assertEquals("#000000", shadow["color"])
    }
}
