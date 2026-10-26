// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** One face the system catalog ships: the (weight, italic) it declares, its file on the classpath, and its declared hash. */
internal data class DeclaredSystemFace(
    val slug: String,
    val weight: Int,
    val italic: Boolean,
    val classpathLocation: String,
    val contentHash: String,
)

/**
 * Every font face the bundled system catalog declares, read from its manifest and font resources as
 * they ship, so the font tests check what is installed rather than a list kept beside it.
 */
internal object SystemCatalogFaces {
    private const val ROOT = "epistola/catalogs/system"

    val ALL: List<DeclaredSystemFace> by lazy {
        val mapper = JsonMapper.builder().build()
        fun read(path: String): JsonNode = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "$path is not on the classpath" }
            .use { mapper.readTree(it) }

        read("$ROOT/catalog.json").get("resources").toList()
            .filter { it.get("type").asString() == "font" }
            .flatMap { entry ->
                val font = read("$ROOT/${entry.get("detailUrl").asString().removePrefix("./")}").get("resource")
                font.get("variants").toList().map { face ->
                    DeclaredSystemFace(
                        slug = font.get("key").asString(),
                        weight = face.get("weight").asInt(),
                        italic = face.get("italic").asBoolean(),
                        classpathLocation = "$ROOT/${face.get("contentUrl").asString().removePrefix("./")}",
                        contentHash = face.get("contentHash").asString(),
                    )
                }
            }
    }
}
