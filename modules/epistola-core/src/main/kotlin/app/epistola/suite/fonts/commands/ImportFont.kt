package app.epistola.suite.fonts.commands

import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

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
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class ImportFontHandler(
    private val jdbi: Jdbi,
    private val fontCatalogWriter: FontCatalogWriter,
) : CommandHandler<ImportFont, InstallStatus> {

    override fun handle(command: ImportFont): InstallStatus = jdbi.inTransaction<InstallStatus, Exception> { handle ->
        fontCatalogWriter.writeFont(
            handle = handle,
            tenantId = command.tenantId,
            catalogKey = command.catalogKey,
            slug = command.slug,
            name = command.name,
            kind = command.kind,
            variants = command.variants,
            assetBytes = { variant ->
                variant.assetKey?.let { assetKey ->
                    GetAssetContent(command.tenantKey, assetKey).query()?.content
                }
            },
        )
    }
}
