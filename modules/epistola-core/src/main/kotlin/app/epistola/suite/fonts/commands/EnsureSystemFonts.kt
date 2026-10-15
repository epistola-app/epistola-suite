// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.commands

import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Seeds the nine bundled open-source font families into a tenant's `system`
 * catalog. Each family ships four static TTF faces on the classpath, by default
 * under `epistola/fonts/<slug>/<slug>-<Token>.ttf`, registered as the canonical CSS
 * faces `(400,false)` / `(700,false)` / `(400,true)` / `(700,true)`; the rows
 * just point at those resources (`FontVariantSource.CLASSPATH`) — no
 * per-tenant binary copy.
 *
 * Idempotent: delegates to [ImportFont], which UPSERTs the `fonts` row and
 * delete-and-reinserts its `font_variants`. Re-running on every boot (via
 * `SystemCatalogBootstrap` → `InstallSystemCatalog`) is a cheap no-op-shaped
 * rewrite.
 *
 * Marked [SystemInternal] like `InstallSystemCatalog`: the caller is framework
 * code, not a user request. The fan-out [ImportFont] commands require
 * `Permission.REFERENCE_EDIT`, so this must run inside the same elevated
 * `SecurityContext` / `CatalogImportContext` that `InstallSystemCatalogHandler`
 * establishes before dispatching it.
 */
data class EnsureSystemFonts(
    val tenantKey: TenantKey,
) : Command<Unit>,
    SystemInternal

/**
 * One bundled family: slug + display name + coarse kind. The four faces are
 * derived by convention (`<slug>-<Variant>.ttf`), unless [location] places them
 * elsewhere on the classpath.
 */
private data class SystemFont(
    val slug: String,
    val name: String,
    val kind: FontKind,
    val location: ((token: String) -> String)? = null,
)

private val SYSTEM_FONTS = listOf(
    SystemFont("inter", "Inter", FontKind.SANS),
    SystemFont("source-sans-3", "Source Sans 3", FontKind.SANS),
    SystemFont("roboto", "Roboto", FontKind.SANS),
    SystemFont("lato", "Lato", FontKind.SANS),
    // Metric-compatible with Helvetica and Arial, so a letter designed in either keeps its
    // widths and line breaks. The renderer already bundles it as its fallback font
    // (modules/generation, `fonts/`), so the faces are registered from there.
    SystemFont("liberation-sans", "Liberation Sans", FontKind.SANS) { token -> "fonts/LiberationSans-$token.ttf" },
    SystemFont("source-serif-4", "Source Serif 4", FontKind.SERIF),
    SystemFont("merriweather", "Merriweather", FontKind.SERIF),
    SystemFont("lora", "Lora", FontKind.SERIF),
    SystemFont("jetbrains-mono", "JetBrains Mono", FontKind.MONO),
)

/**
 * The four bundled faces per family: CSS (weight, italic) → static TTF
 * filename token (`<slug>-<token>.ttf`). The bundled font *files* are
 * unchanged — only their addressing moved to numeric weight + italic.
 */
private data class BundledFace(val weight: Int, val italic: Boolean, val token: String)

private val BUNDLED_FACES = listOf(
    BundledFace(weight = 400, italic = false, token = "Regular"),
    BundledFace(weight = 700, italic = false, token = "Bold"),
    BundledFace(weight = 400, italic = true, token = "Italic"),
    BundledFace(weight = 700, italic = true, token = "BoldItalic"),
)

/** A bundled face as the seeder registers it: the CSS (weight, italic) it answers for. */
internal data class DeclaredSystemFace(
    val slug: String,
    val weight: Int,
    val italic: Boolean,
    val classpathLocation: String,
)

@Component
class EnsureSystemFontsHandler(
    private val jdbi: Jdbi,
    private val fontCatalogWriter: FontCatalogWriter,
) : CommandHandler<EnsureSystemFonts, Unit> {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** The single addressing convention for a bundled face's classpath resource. */
        private fun classpathLocation(font: SystemFont, token: String): String = font.location?.invoke(token) ?: "epistola/fonts/${font.slug}/${font.slug}-$token.ttf"

        /**
         * Every face the seeder will register, from the exact same `SYSTEM_FONTS` ×
         * `BUNDLED_FACES` cross-product the handler uses (via the shared
         * [classpathLocation]). Exposed (`internal`) so guard tests can check the
         * bundled files against what they are registered as, without duplicating the list.
         */
        internal val DECLARED_FACES: List<DeclaredSystemFace> =
            SYSTEM_FONTS.flatMap { font ->
                BUNDLED_FACES.map { face ->
                    DeclaredSystemFace(font.slug, face.weight, face.italic, classpathLocation(font, face.token))
                }
            }

        /** Every classpath resource the seeder will register; see [DECLARED_FACES]. */
        internal val DECLARED_CLASSPATH_LOCATIONS: List<String> = DECLARED_FACES.map { it.classpathLocation }
    }

    override fun handle(command: EnsureSystemFonts) {
        val tenantId = TenantId(command.tenantKey)
        // All bundled families in ONE transaction and three statements via the
        // shared writer. The faces are all classpath, hashed from the writer's per-JVM cache.
        val fonts = SYSTEM_FONTS.map { font ->
            FontCatalogWriter.FontSpec(
                slug = font.slug,
                name = font.name,
                kind = font.kind.wire,
                variants = BUNDLED_FACES.map { face ->
                    ImportFontVariant(
                        weight = face.weight,
                        italic = face.italic,
                        source = FontVariantSource.CLASSPATH,
                        classpathLocation = classpathLocation(font, face.token),
                    )
                },
            )
        }
        jdbi.inTransaction<Unit, Exception> { handle ->
            fontCatalogWriter.writeFonts(
                handle = handle,
                tenantId = tenantId,
                catalogKey = SYSTEM_CATALOG_KEY,
                fonts = fonts,
            )
        }
        log.debug(
            "Ensured {} system font families for tenant {}",
            SYSTEM_FONTS.size,
            command.tenantKey.value,
        )
    }
}
