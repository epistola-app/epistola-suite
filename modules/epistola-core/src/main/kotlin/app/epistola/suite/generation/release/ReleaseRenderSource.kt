// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.release

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.generation.pdf.FontFamilyResolver
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.generation.pdf.ResolvedTheme
import app.epistola.generation.pdf.SpacingScale
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.catalog.revisions.ReleaseDependencyStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.queries.pickNearestFace
import app.epistola.suite.tenants.Tenant
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import com.github.benmanes.caffeine.cache.Caffeine
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.Optional

/** The release a render reads: a version of one catalog. */
data class ReleaseRef(val catalogKey: CatalogKey, val version: String)

/**
 * Everything the renderer needs for one template variant, taken from one catalog release.
 *
 * Nothing here is read from the working copy. The theme, fonts and images come from the release
 * itself or, for another catalog's resources, from the release of that catalog this release pinned
 * when it was cut.
 */
data class ReleaseRenderInputs(
    val release: ReleaseRef,
    /**
     * The template's identity as the release recorded it. Generation history names it, because the
     * template may since have moved or been deleted from the working copy while the release still
     * holds it.
     */
    val templateResourceId: java.util.UUID,
    val templateName: String,
    val templateModel: TemplateDocument,
    val variantAttributes: Map<String, String>,
    val pdfaEnabled: Boolean,
    val resolvedTheme: ResolvedTheme,
    val renderingDefaults: RenderingDefaults,
    /** The template's data contract as the release holds it, or null when it has none. */
    val dataModel: ObjectNode?,
    /** The template's first data example as the release holds it: what a preview without data renders. */
    val firstDataExample: ObjectNode?,
    val assetResolver: AssetResolver,
    val fontFamilyResolver: FontFamilyResolver,
)

/** A release cannot be rendered: something it needs is not in it, or it kept no content. */
class ReleaseRenderException(message: String) : IllegalStateException(message)

/**
 * Builds [ReleaseRenderInputs] from a catalog release's retained content.
 *
 * **Which release a resource comes from.** A reference inside the catalog resolves in the release
 * being rendered. A reference into another catalog resolves in the release this one pinned for that
 * catalog (`release_dependencies`). A release cut before pins were recorded has none, and falls back
 * to the other catalog's latest release that kept its content.
 *
 * **Caching.** Revisions are immutable, so a resource read from a release never changes while that
 * release exists. Both caches are bounded, and expire on idle so content dropped by forgetting or
 * deleting a release does not linger.
 */
@Component
class ReleaseRenderSource(
    private val jdbi: Jdbi,
    private val assembler: ReleaseContentAssembler,
    private val dependencyStore: ReleaseDependencyStore,
    private val objectMapper: ObjectMapper,
) {

    private val resources = Caffeine.newBuilder()
        .maximumSize(MAX_RESOURCES)
        .expireAfterAccess(Duration.ofMinutes(10))
        .build<String, Optional<CatalogResource>>()

    private val binaries = Caffeine.newBuilder()
        .maximumWeight(MAX_BINARY_BYTES)
        .weigher<String, ByteArray> { _, value -> value.size }
        .expireAfterAccess(Duration.ofMinutes(10))
        .build<String, ByteArray>()

    /**
     * The inputs for rendering [variantKey] of [templateKey] from [release].
     *
     * @param tenant supplies the tenant default theme a template without a theme of its own falls
     *   back to. It is tenant configuration, not catalog content.
     * @throws ReleaseRenderException when the release does not contain the template or variant, or
     *   names a theme, catalog or binary it cannot supply.
     */
    fun resolve(tenant: Tenant, release: ReleaseRef, templateKey: String, variantKey: String): ReleaseRenderInputs {
        val tenantKey = tenant.id
        val renderingDefaultsVersion = loadRenderingDefaultsVersion(tenantKey, release)
        val pins = jdbi.withHandle<Map<CatalogKey, String>, Exception> { handle ->
            dependencyStore.pinsOf(handle, tenantKey, release.catalogKey, release.version)
        }
        val scope = Scope(tenantKey, release, pins)

        val template = read(scope.tenantKey, release, "template", templateKey) as? TemplateResource
            ?: throw ReleaseRenderException("Release ${release.label()} does not contain template '$templateKey'")
        val variant = template.variants.firstOrNull { it.slug == variantKey }
            ?: throw ReleaseRenderException("Release ${release.label()} has no variant '$variantKey' of template '$templateKey'")
        // The wire form hoists the default variant's model onto the template and leaves it off the
        // variant entry, so the default variant's model is the template's.
        val model = variant.templateModel ?: template.templateModel

        val (themeCatalog, theme) = resolveTheme(scope, tenant, template, model)

        val templateResourceId = jdbi.withHandle<java.util.UUID, Exception> { handle ->
            handle.createQuery(
                """
                SELECT resource_id FROM release_entries
                WHERE tenant_key = :t AND catalog_key = :c AND version = :version
                  AND resource_type = 'template' AND resource_key = :key
                """,
            )
                .bind("t", tenantKey)
                .bind("c", release.catalogKey)
                .bind("version", release.version)
                .bind("key", templateKey)
                .mapTo(java.util.UUID::class.java)
                .one()
        }

        return ReleaseRenderInputs(
            release = release,
            templateResourceId = templateResourceId,
            templateName = template.name,
            templateModel = model,
            variantAttributes = variant.attributes.orEmpty(),
            pdfaEnabled = template.pdfaEnabled,
            resolvedTheme = resolvedTheme(theme, model),
            renderingDefaults = renderingDefaultsVersion?.let(RenderingDefaults::forVersion) ?: RenderingDefaults.CURRENT,
            dataModel = template.dataModel?.let { objectMapper.valueToTree<ObjectNode>(it) },
            firstDataExample = template.dataExamples?.firstOrNull()?.data?.let { objectMapper.valueToTree<ObjectNode>(it) },
            assetResolver = assetResolver(scope),
            fontFamilyResolver = fontResolver(scope, owningCatalog = themeCatalog ?: release.catalogKey),
        )
    }

    /**
     * The theme cascade, read from releases: the variant's own theme, then the template's, then the
     * tenant default. Returns the theme's catalog alongside it, because unqualified font references
     * resolve in the catalog of the theme that styles the document.
     */
    private fun resolveTheme(scope: Scope, tenant: Tenant, template: TemplateResource, model: TemplateDocument): Pair<CatalogKey?, ThemeResource?> {
        val ref = model.themeRef
        val (themeKey, themeCatalog) = when {
            ref is ThemeRefOverride -> ref.themeId to ref.catalogKey?.let(CatalogKey::of)
            template.themeId != null -> template.themeId!! to template.themeCatalogKey?.let(CatalogKey::of)
            tenant.defaultThemeKey != null -> tenant.defaultThemeKey.value to tenant.defaultThemeCatalogKey
            else -> return null to null
        }
        val catalog = themeCatalog ?: scope.release.catalogKey
        val theme = read(scope.tenantKey, scope.releaseOf(catalog), "theme", themeKey) as? ThemeResource
            ?: throw ReleaseRenderException(
                "Release ${scope.release.label()} renders with theme '$themeKey' of catalog '${catalog.value}', " +
                    "which ${scope.releaseOf(catalog).label()} does not contain",
            )
        return catalog to theme
    }

    /** Theme styles as defaults, the template's own document styles on top: the same merge as live rendering. */
    private fun resolvedTheme(theme: ThemeResource?, model: TemplateDocument): ResolvedTheme {
        val override = model.documentStylesOverride.orEmpty()
        if (theme == null) return ResolvedTheme(documentStyles = override)
        val themeStyles = theme.documentStyles.orEmpty().filterValues { it != null }.mapValues { (_, v) -> v!! }
        return ResolvedTheme(
            documentStyles = themeStyles + override,
            pageSettings = theme.pageSettings,
            blockStylePresets = theme.blockStylePresets.orEmpty().mapValues { (_, preset) -> preset.styles.orEmpty() },
            spacingUnit = theme.spacingUnit ?: SpacingScale.DEFAULT_BASE_UNIT,
        )
    }

    /**
     * Images by key. A reference that names its catalog resolves in that catalog's release. One that
     * does not resolves in this release first and then in the releases it pinned, in catalog order:
     * an unqualified reference used to resolve tenant-wide, and a release can only offer what it and
     * its pins hold.
     */
    private fun assetResolver(scope: Scope): AssetResolver = AssetResolver { assetId, catalogKey ->
        val candidates = if (catalogKey != null) {
            listOf(scope.releaseOf(CatalogKey.of(catalogKey)))
        } else {
            listOf(scope.release) + scope.pins.entries.sortedBy { it.key.value }.map { ReleaseRef(it.key, it.value) }
        }
        candidates.firstNotNullOfOrNull { release ->
            (read(scope.tenantKey, release, "image", assetId) as? ImageResource)?.let { image ->
                binary(scope.tenantKey, image.contentHash)?.let { AssetResolution(it, image.mediaType) }
            }
        }
    }

    /** Font faces by family, nearest weight first, bytes by content hash. */
    private fun fontResolver(scope: Scope, owningCatalog: CatalogKey): FontFamilyResolver = FontFamilyResolver { catalogKey, slug, weight, italic ->
        val catalog = catalogKey?.let(CatalogKey::of) ?: owningCatalog
        val family = read(scope.tenantKey, scope.releaseOf(catalog), "font", slug) as? FontResource
            ?: return@FontFamilyResolver null
        val face = pickNearestFace(family.variants, { it.weight }, { it.italic }, weight, italic)
            ?: return@FontFamilyResolver null
        binary(scope.tenantKey, face.contentHash)
    }

    private fun read(tenantKey: TenantKey, release: ReleaseRef, type: String, key: String): CatalogResource? = resources.get(
        "${tenantKey.value}|${release.catalogKey.value}|${release.version}|$type|$key",
    ) {
        Optional.ofNullable(assembler.readResource(tenantKey, release.catalogKey, release.version, type, key))
    }.orElse(null)

    private fun binary(tenantKey: TenantKey, contentHash: String): ByteArray? {
        val key = "${tenantKey.value}|$contentHash"
        binaries.getIfPresent(key)?.let { return it }
        val bytes = assembler.readBinary(tenantKey, contentHash) ?: return null
        binaries.put(key, bytes)
        return bytes
    }

    private fun loadRenderingDefaultsVersion(tenantKey: TenantKey, release: ReleaseRef): Int? = jdbi.withHandle<Int?, Exception> { handle ->
        val row = handle.createQuery(
            """
            SELECT content_retained, rendering_defaults_version FROM catalog_releases
            WHERE tenant_key = :t AND catalog_key = :c AND version = :version
            """,
        )
            .bind("t", tenantKey)
            .bind("c", release.catalogKey)
            .bind("version", release.version)
            .map { rs, _ -> rs.getBoolean("content_retained") to rs.getObject("rendering_defaults_version") as Int? }
            .findOne()
            .orElse(null) ?: throw ReleaseRenderException("Release ${release.label()} does not exist")
        if (!row.first) throw ReleaseRenderException("Release ${release.label()} did not keep its content and cannot be rendered")
        row.second
    }

    /** Where a catalog's resources come from while rendering one release. */
    private inner class Scope(val tenantKey: TenantKey, val release: ReleaseRef, val pins: Map<CatalogKey, String>) {
        fun releaseOf(catalog: CatalogKey): ReleaseRef = when {
            catalog == release.catalogKey -> release

            pins[catalog] != null -> ReleaseRef(catalog, pins.getValue(catalog))

            else -> ReleaseRef(
                catalog,
                latestRetained(tenantKey, catalog)
                    ?: throw ReleaseRenderException(
                        "Release ${release.label()} uses catalog '${catalog.value}', which has no release that kept its content",
                    ),
            )
        }
    }

    private fun latestRetained(tenantKey: TenantKey, catalog: CatalogKey): String? = jdbi.withHandle<String?, Exception> { handle ->
        dependencyStore.latestRetainedRelease(handle, tenantKey, catalog)
    }

    private companion object {
        const val MAX_RESOURCES = 2_000L
        const val MAX_BINARY_BYTES = 64L * 1024 * 1024
    }
}

private fun ReleaseRef.label(): String = "${catalogKey.value}@$version"
