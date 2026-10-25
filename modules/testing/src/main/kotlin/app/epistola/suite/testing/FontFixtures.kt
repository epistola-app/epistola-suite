// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.mediator.execute

/** Fonts for tests, created as an author creates them. Call inside a mediator scope. */
object FontFixtures {
    /** Creates or replaces font [slug] of [catalog] with [faces], TTF bytes by (weight, italic). */
    fun importFont(catalog: CatalogId, slug: String, name: String, faces: Map<Pair<Int, Boolean>, ByteArray>) {
        val variants = faces.map { (face, bytes) -> ImportFontVariant(face.first, face.second, "font/ttf", bytes) }
        ImportFont(catalog, slug = slug, name = name, kind = FontKind.SANS.wire, variants = variants).execute()
    }
}
