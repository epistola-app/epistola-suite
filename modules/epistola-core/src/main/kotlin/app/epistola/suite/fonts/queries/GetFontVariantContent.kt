package app.epistola.suite.fonts.queries

import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.FontVariant
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves a single font-face's binary bytes.
 *
 * - `ASSET`     — dispatches to `GetAssetContent` (the face is an ordinary
 *   uploaded asset in the same catalog).
 * - `CLASSPATH` — reads the bundled JAR resource at `classpath_location`.
 *
 * Returns null when the variant row, asset, or classpath resource is missing.
 */
data class GetFontVariantContent(
    val tenantId: TenantKey,
    val catalogKey: CatalogKey,
    val slug: FontKey,
    val variant: FontVariant,
) : Query<ByteArray?>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetFontVariantContentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFontVariantContent, ByteArray?> {

    private data class VariantPointer(
        val source: FontVariantSource,
        val assetKey: AssetKey?,
        val classpathLocation: String?,
    )

    override fun handle(query: GetFontVariantContent): ByteArray? {
        val pointer = jdbi.withHandle<VariantPointer?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT source, asset_key, classpath_location
                FROM font_variants
                WHERE tenant_key = :tenantKey
                  AND catalog_key = :catalogKey
                  AND font_slug = :slug
                  AND variant = :variant
                """,
            )
                .bind("tenantKey", query.tenantId)
                .bind("catalogKey", query.catalogKey)
                .bind("slug", query.slug)
                .bind("variant", query.variant.wire)
                .map { rs, _ ->
                    VariantPointer(
                        source = FontVariantSource.valueOf(rs.getString("source")),
                        assetKey = rs.getObject("asset_key", UUID::class.java)?.let(::AssetKey),
                        classpathLocation = rs.getString("classpath_location"),
                    )
                }
                .findOne()
                .orElse(null)
        } ?: return null

        return when (pointer.source) {
            FontVariantSource.CLASSPATH -> pointer.classpathLocation?.let { location ->
                this::class.java.classLoader.getResourceAsStream(location)?.readBytes()
            }
            FontVariantSource.ASSET -> pointer.assetKey?.let { assetKey ->
                GetAssetContent(query.tenantId, assetKey).query()?.content
            }
        }
    }
}
