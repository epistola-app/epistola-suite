package app.epistola.suite.fonts.commands

import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.model.sha256Hex
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * A single font-face pointer carried by [ImportFont], keyed by CSS numeric
 * [weight] (1–1000; 400 = regular, 700 = bold) + [italic]. Exactly one of
 * [assetKey] / [classpathLocation] is non-null, matching the SQL CHECK.
 * Every face is a static binary (variable fonts are instanced at upload).
 */
data class ImportFontVariant(
    val weight: Int,
    val italic: Boolean,
    val source: FontVariantSource,
    val assetKey: AssetKey? = null,
    val classpathLocation: String? = null,
)

/**
 * Catalog-import counterpart for font families — UPSERTs a `fonts` row and
 * (delete-and-reinsert) its `font_variants`. Matches the `ImportCodeList`
 * shape used by `InstallFromCatalog` / `ImportCatalogZip`.
 *
 * `kind` arrives as the lowercase wire string (`sans` / `serif` / …) and is
 * persisted as-is to satisfy the `chk_fonts_kind` SQL CHECK. Re-running this
 * command (e.g. a catalog upgrade) replaces the variant set atomically.
 */
data class ImportFont(
    val tenantId: TenantId,
    val catalogKey: CatalogKey,
    val slug: String,
    val name: String,
    val kind: String,
    val variants: List<ImportFontVariant> = emptyList(),
) : Command<InstallStatus>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ImportFontHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ImportFont, InstallStatus> {

    override fun handle(command: ImportFont): InstallStatus {
        val fontSlug = FontKey.of(command.slug)
        // Normalise through the enum so an unknown kind fails loudly here
        // rather than as an opaque CHECK violation.
        val kindWire = FontKind.fromWire(command.kind).wire

        return jdbi.inTransaction<InstallStatus, Exception> { handle ->
            val exists = handle.createQuery(
                """
                SELECT COUNT(*) > 0
                FROM fonts
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("slug", fontSlug)
                .mapTo(Boolean::class.java)
                .one()

            handle.createUpdate(
                """
                INSERT INTO fonts (slug, tenant_key, catalog_key, name, kind, created_at, updated_at)
                VALUES (:slug, :tenantKey, :catalogKey, :name, :kind, NOW(), NOW())
                ON CONFLICT (tenant_key, catalog_key, slug) DO UPDATE
                SET name       = :name,
                    kind       = :kind,
                    updated_at = NOW()
                """,
            )
                .bind("slug", fontSlug)
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("name", command.name)
                .bind("kind", kindWire)
                .execute()

            // Replace variants atomically — the importing tenant has no local
            // copy to preserve; the catalog file is the source of record.
            handle.createUpdate(
                """
                DELETE FROM font_variants
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND font_slug = :slug
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("slug", fontSlug)
                .execute()

            if (command.variants.isNotEmpty()) {
                val batch = handle.prepareBatch(
                    """
                    INSERT INTO font_variants
                        (tenant_key, catalog_key, font_slug, weight, italic, source, asset_key, classpath_location, content_hash)
                    VALUES (:tenantKey, :catalogKey, :slug, :weight, :italic, :source, :assetKey, :classpathLocation, :contentHash)
                    """,
                )
                for (variant in command.variants) {
                    batch.bind("tenantKey", command.tenantKey)
                        .bind("catalogKey", command.catalogKey)
                        .bind("slug", fontSlug)
                        .bind("weight", variant.weight)
                        .bind("italic", variant.italic)
                        .bind("source", variant.source.name)
                        .bind("assetKey", variant.assetKey?.value)
                        .bind("classpathLocation", variant.classpathLocation)
                        .bind("contentHash", contentHash(command.tenantKey, variant))
                        .add()
                }
                batch.execute()
            }

            if (exists) InstallStatus.UPDATED else InstallStatus.INSTALLED
        }
    }

    /**
     * SHA-256 hex of a variant's bytes, stored into `content_hash` so a
     * published version can pin (and later verify) the exact face binary.
     *
     * Resilient by design: this is a *metadata* import. If the bytes cannot be
     * loaded (missing classpath resource / asset not yet readable) we store
     * `null` rather than failing the import — the family fingerprint treats a
     * null hash as a mismatch, so a never-hashed face still fails a published
     * render loudly instead of silently rendering wrong.
     *
     * A classpath face's bytes are immutable for the life of the JVM and the
     * system catalog re-imports the same faces into every tenant, so its hash is
     * cached by location ([classpathContentHash]) — otherwise each tenant
     * bootstrap re-reads and re-hashes the same bundled TTFs (the dominant cost
     * of `EnsureSystemFonts`). Asset faces are hashed live (their bytes are
     * tenant-owned and mutable).
     */
    private fun contentHash(tenantKey: TenantKey, variant: ImportFontVariant): String? = when (variant.source) {
        FontVariantSource.CLASSPATH -> variant.classpathLocation?.let(::classpathContentHash)
        FontVariantSource.ASSET -> variant.assetKey?.let { assetKey ->
            runCatching { GetAssetContent(tenantKey, assetKey).query()?.content }
                .getOrNull()?.let(::sha256Hex)
        }
    }

    private fun classpathContentHash(location: String): String? = CLASSPATH_HASH_CACHE.computeIfAbsent(location) { loc ->
        Optional.ofNullable(
            runCatching { ImportFontHandler::class.java.classLoader.getResourceAsStream(loc)?.readBytes() }
                .getOrNull()
                ?.let(::sha256Hex),
        )
    }.orElse(null)

    private companion object {
        /** location -> SHA-256 hex (absent value = unreadable), computed once per JVM. */
        private val CLASSPATH_HASH_CACHE = ConcurrentHashMap<String, Optional<String>>()
    }
}
