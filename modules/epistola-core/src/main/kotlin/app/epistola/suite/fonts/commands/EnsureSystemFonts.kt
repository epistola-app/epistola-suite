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
 * Seeds the eight bundled open-source font families into a tenant's `system`
 * catalog. Each family ships four static TTF faces on the classpath under
 * `epistola/fonts/<slug>/<slug>-<Token>.ttf`, registered as the canonical CSS
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
 * `Permission.TENANT_SETTINGS`, so this must run inside the same elevated
 * `SecurityContext` / `CatalogImportContext` that `InstallSystemCatalogHandler`
 * establishes before dispatching it.
 */
data class EnsureSystemFonts(
    val tenantKey: TenantKey,
) : Command<Unit>,
    SystemInternal

/**
 * One bundled family: slug + display name + coarse kind. The four faces are
 * derived by convention (`<slug>-<Variant>.ttf`).
 */
private data class SystemFont(
    val slug: String,
    val name: String,
    val kind: FontKind,
)

private val SYSTEM_FONTS = listOf(
    SystemFont("inter", "Inter", FontKind.SANS),
    SystemFont("source-sans-3", "Source Sans 3", FontKind.SANS),
    SystemFont("roboto", "Roboto", FontKind.SANS),
    SystemFont("lato", "Lato", FontKind.SANS),
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

@Component
class EnsureSystemFontsHandler(
    private val jdbi: Jdbi,
    private val fontCatalogWriter: FontCatalogWriter,
) : CommandHandler<EnsureSystemFonts, Unit> {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** The single addressing convention for a bundled face's classpath resource. */
        private fun classpathLocation(slug: String, token: String): String = "epistola/fonts/$slug/$slug-$token.ttf"

        /**
         * Every classpath resource the seeder will register, derived from the
         * exact same `SYSTEM_FONTS` × `BUNDLED_FACES` cross-product the handler
         * uses (via the shared [classpathLocation]). Exposed (`internal`) so a
         * guard test can assert the hardcoded list stays in sync with the
         * bundled resource tree without duplicating the list.
         */
        internal val DECLARED_CLASSPATH_LOCATIONS: List<String> =
            SYSTEM_FONTS.flatMap { font ->
                BUNDLED_FACES.map { face -> classpathLocation(font.slug, face.token) }
            }
    }

    override fun handle(command: EnsureSystemFonts) {
        val tenantId = TenantId(command.tenantKey)
        // All eight bundled families in ONE transaction via the shared writer —
        // previously this dispatched one ImportFont command (and one transaction +
        // existence SELECT) per family. The faces are all classpath, hashed from
        // the writer's per-JVM cache.
        jdbi.inTransaction<Unit, Exception> { handle ->
            for (font in SYSTEM_FONTS) {
                val variants = BUNDLED_FACES.map { face ->
                    ImportFontVariant(
                        weight = face.weight,
                        italic = face.italic,
                        source = FontVariantSource.CLASSPATH,
                        classpathLocation = classpathLocation(font.slug, face.token),
                    )
                }
                fontCatalogWriter.writeFont(
                    handle = handle,
                    tenantId = tenantId,
                    catalogKey = SYSTEM_CATALOG_KEY,
                    slug = font.slug,
                    name = font.name,
                    kind = font.kind.wire,
                    variants = variants,
                )
            }
        }
        log.debug(
            "Ensured {} system font families for tenant {}",
            SYSTEM_FONTS.size,
            command.tenantKey.value,
        )
    }
}
