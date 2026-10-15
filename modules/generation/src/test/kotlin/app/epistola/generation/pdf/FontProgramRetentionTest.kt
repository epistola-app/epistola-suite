// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.catalog.protocol.FontRef
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.io.font.FontCache
import com.itextpdf.io.font.FontCacheKey
import com.itextpdf.io.font.FontProgram
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * iText's font cache is static and never evicts, so a font parsed into it stays for the life of the
 * JVM; a parsed face holds tens of MB. Rendering and upload validation must leave it alone and go
 * through the renderer's bounded [FontProgramCache] instead.
 */
class FontProgramRetentionTest {

    /** Liberation Sans Bold plus padding, so no other test produces the same content key. */
    private val uniqueFont: ByteArray =
        javaClass.getResourceAsStream("/fonts/LiberationSans-Bold.ttf")!!.readBytes() + ByteArray(7) { 0x2A }

    private val resolver = FontFamilyResolver { _, _, _, _ -> uniqueFont }

    private fun keptByIText(bytes: ByteArray): FontProgram? {
        val getFont = FontCache::class.java.getDeclaredMethod("getFont", FontCacheKey::class.java)
        getFont.isAccessible = true
        return getFont.invoke(null, FontCacheKey.create(bytes)) as FontProgram?
    }

    private val document = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
            "text" to Node(
                id = "text",
                type = "text",
                props = mapOf(
                    "content" to mapOf(
                        "type" to "doc",
                        "content" to listOf(
                            mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "José Gómez"))),
                        ),
                    ),
                ),
            ),
        ),
        slots = mapOf("slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("text"))),
    )

    private val theme = ResolvedTheme(documentStyles = mapOf("fontFamily" to mapOf("slug" to "unique", "catalogKey" to "system")))

    @Test
    fun `rendering with a font leaves it out of iText's font cache`() {
        DirectPdfRenderer().render(
            document,
            emptyMap(),
            OutputStream.nullOutputStream(),
            resolvedTheme = theme,
            pdfaCompliant = true,
            fontFamilyResolver = resolver,
        )

        assertNull(keptByIText(uniqueFont))
    }

    @Test
    fun `validating an upload leaves it out of iText's font cache`() {
        assertNull(FontBytesValidator.rejectionReason(uniqueFont))

        assertNull(keptByIText(uniqueFont))
    }

    @Test
    fun `renders sharing a renderer reuse its parsed font`() {
        val programs = FontProgramCache()
        val first = FontCache(pdfaCompliant = true, fontFamilyResolver = resolver, fontPrograms = programs.scope())
        val second = FontCache(pdfaCompliant = true, fontFamilyResolver = resolver, fontPrograms = programs.scope())
        val ref = FontRef(slug = "unique", catalogKey = "system")

        val program = first.font(ref, 400, false).fontProgram
        assertNotNull(program)
        assertSame(program, second.font(ref, 400, false).fontProgram)
    }

    @Test
    fun `a render keys the parsed font by the hash its resolver supplies`() {
        val programs = FontProgramCache()
        val hashing = object : FontFamilyResolver {
            override fun resolve(catalogKey: String?, slug: String, weight: Int, italic: Boolean) = uniqueFont
            override fun resolveFace(catalogKey: String?, slug: String, weight: Int, italic: Boolean) = FontFaceBytes(uniqueFont, "stored-hash")
        }
        val rendered = FontCache(pdfaCompliant = true, fontFamilyResolver = hashing, fontPrograms = programs.scope())
            .font(FontRef(slug = "unique", catalogKey = "system"), 400, false).fontProgram

        // Other bytes under the same hash: only a lookup by that hash can return the rendered program.
        assertSame(rendered, programs.scope().program(ByteArray(1), "stored-hash"))
    }
}
