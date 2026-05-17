package app.epistola.suite.fonts.commands

import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * A single font-face pointer carried by [ImportFont]. Exactly one of
 * [assetKey] / [classpathLocation] is non-null, matching the SQL CHECK.
 */
data class ImportFontVariant(
    val variant: String,
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
                        (tenant_key, catalog_key, font_slug, variant, source, asset_key, classpath_location)
                    VALUES (:tenantKey, :catalogKey, :slug, :variant, :source, :assetKey, :classpathLocation)
                    """,
                )
                for (variant in command.variants) {
                    batch.bind("tenantKey", command.tenantKey)
                        .bind("catalogKey", command.catalogKey)
                        .bind("slug", fontSlug)
                        .bind("variant", variant.variant)
                        .bind("source", variant.source.name)
                        .bind("assetKey", variant.assetKey?.value)
                        .bind("classpathLocation", variant.classpathLocation)
                        .add()
                }
                batch.execute()
            }

            if (exists) InstallStatus.UPDATED else InstallStatus.INSTALLED
        }
    }
}
