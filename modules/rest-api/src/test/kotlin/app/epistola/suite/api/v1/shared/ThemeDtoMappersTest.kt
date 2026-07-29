// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.Theme
import app.epistola.template.model.BlockStylePreset
import app.epistola.template.model.PageSettings
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ThemeDtoMappersTest {
    @Test
    fun `wraps portable block style preset values without converting them`() {
        val preset =
            BlockStylePreset(
                label = "Notice",
                styles =
                mapOf(
                    "fontSize" to "12pt",
                    "keepTogether" to true,
                    "opacity" to 0.5,
                    "shadow" to mapOf("color" to "#000000"),
                ),
                applicableTo = listOf("paragraph"),
            )
        val presets =
            mapOf(
                "notice" to preset,
            )

        val converted = assertNotNull(presets.toDomainPresets())
        val convertedPreset = assertNotNull(converted["notice"])

        assertSame(preset, convertedPreset)
        assertEquals("Notice", convertedPreset.label)
        assertEquals(listOf("paragraph"), convertedPreset.applicableTo)
        assertEquals("12pt", convertedPreset.styles["fontSize"])
        assertEquals(true, convertedPreset.styles["keepTogether"])
        assertEquals(0.5, (convertedPreset.styles["opacity"] as Number).toDouble())
        val shadow = assertNotNull(convertedPreset.styles["shadow"] as? Map<*, *>)
        assertEquals("#000000", shadow["color"])
    }

    @Test
    fun `theme DTO retains portable catalog values`() {
        val styles = mapOf<String, Any>("fontFamily" to "Inter")
        val pageSettings = PageSettings(backgroundColor = "#ffffff")
        val preset = BlockStylePreset(label = "Notice", styles = mapOf("fontSize" to "12pt"))
        val presets = BlockStylePresets(mapOf("notice" to preset))
        val timestamp = OffsetDateTime.parse("2026-07-28T10:00:00Z")
        val theme = Theme(
            id = ThemeKey.of("default"),
            tenantKey = TenantKey.of("tenant"),
            name = "Default",
            description = null,
            documentStyles = styles,
            pageSettings = pageSettings,
            blockStylePresets = presets,
            spacingUnit = 4f,
            createdAt = timestamp,
            updatedAt = timestamp,
        )

        val dto = theme.toDto()

        assertSame(styles, dto.documentStyles)
        assertSame(pageSettings, dto.pageSettings)
        assertSame(preset, dto.blockStylePresets?.get("notice"))
    }
}
