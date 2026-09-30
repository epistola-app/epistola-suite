// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1030: a block style preset that sets `fontWeight` without `fontFamily` rendered its block
 * in the built-in bold (Helvetica-Bold / Liberation Sans Bold) instead of the theme's family.
 * The theme family must survive; versions published before V5 keep their historical output.
 */
class PresetFontInheritanceTest {

    private val liberationRegular: ByteArray =
        PresetFontInheritanceTest::class.java.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.readBytes()

    private fun render(defaults: RenderingDefaults): Pair<Set<String>, String> {
        val calls = ConcurrentHashMap.newKeySet<String>()
        val resolver = FontFamilyResolver { _, slug, weight, italic ->
            calls.add("$slug|$weight|$italic")
            liberationRegular
        }
        val text = Node(
            id = "label",
            type = "text",
            stylePreset = "label",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "Kenmerk"))),
                    ),
                ),
            ),
        )
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot")), "label" to text),
            slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("label"))),
        )
        val output = ByteArrayOutputStream()
        DirectPdfRenderer().render(
            document = document,
            data = emptyMap(),
            outputStream = output,
            resolvedTheme = ResolvedTheme(
                documentStyles = mapOf("fontFamily" to mapOf("slug" to "source-sans-3", "catalogKey" to "system")),
                blockStylePresets = mapOf("label" to mapOf("label" to "Label", "fontWeight" to 700)),
            ),
            fontFamilyResolver = resolver,
            renderingDefaults = defaults,
        )
        return calls to output.toByteArray().decodeToString()
    }

    @Test
    fun `a bold-only preset renders in the theme family's bold face`() {
        val (calls, pdf) = render(RenderingDefaults.V5)

        assertTrue("source-sans-3|700|false" in calls, "The preset's weight must resolve through the theme family; calls=$calls")
        assertFalse("/Helvetica-Bold" in pdf, "The built-in bold must not replace the theme family")
    }

    @Test
    fun `a version published under V4 keeps rendering the built-in bold`() {
        val (_, pdf) = render(RenderingDefaults.V4)

        assertTrue("/Helvetica-Bold" in pdf, "Published output must not change under an older defaults version")
    }
}
