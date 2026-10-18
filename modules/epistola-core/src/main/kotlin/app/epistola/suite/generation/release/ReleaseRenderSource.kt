// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.release

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.generation.pdf.FontFaceBytes
import app.epistola.generation.pdf.FontFamilyResolver
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.generation.pdf.ResolvedTheme
import app.epistola.generation.pdf.SpacingScale
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.queries.LATEST_RELEASE_ORDER
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.queries.pickNearestFace
import app.epistola.suite.stencils.model.StencilInstances
import app.epistola.suite.tenants.Tenant
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import com.github.benmanes.caffeine.cache.Caffeine
import org.jdbi.v3.core.Handle
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
 * being rendered. A reference into another catalog resolves in the release of that catalog the
 * request's environment deploys, or else in that catalog's latest release that kept its content,
 * read once per render: a release does not lock the catalogs it borrows from, the environment
 * chooses.
 *
 * **Caching.** Revisions are immutable, so a resource read from a release never changes while that
 * release exists. Both caches are bounded, and expire on idle so content dropped by forgetting or
 * deleting a release does not linger.
 */
@Component
class ReleaseRenderSource(
    private val jdbi: Jdbi,
    private val assembler: ReleaseContentAssembler,
    private val entryStore: ReleaseEntryStore,
    private val objectMapper: ObjectMapper,
    private val dependencyStore: CatalogDependencyStore,
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
     * @param environment the request's environment, which chooses the release of every other
     *   catalog the template borrows from; null renders those from their latest release.
     * @throws ReleaseRenderException when the release does not contain the template or variant, or
     *   names a theme, catalog or binary it cannot supply.
     */
    fun resolve(tenant: Tenant, release: ReleaseRef, templateKey: String, variantKey: String, environment: EnvironmentKey? = null): ReleaseRenderInputs {
        val tenantKey = tenant.id
        val renderingDefaultsVersion = loadRenderingDefaultsVersion(tenantKey, release)
        val scope = Scope(tenantKey, release, environment)

        val template = read(scope.tenantKey, release, "template", templateKey) as? TemplateResource
            ?: throw ReleaseRenderException("Release ${release.label()} does not contain template '$templateKey'")
        val variant = template.variants.firstOrNull { it.key == variantKey }
            ?: throw ReleaseRenderException("Release ${release.label()} has no variant '$variantKey' of template '$templateKey'")
        // The wire form hoists the default variant's model onto the template and leaves it off the
        // variant entry, so the default variant's model is the template's.
        val model = expandStencils(scope, variant.templateModel ?: template.templateModel)

        val (themeCatalog, theme) = resolveTheme(scope, tenant, template, model)

        val templateResourceId = requireNotNull(templateResourceId(tenantKey, release, templateKey)) {
            "Release ${release.label()} has no entry for template '$templateKey'"
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
     * The template with its stencil instances expanded (contract ADR 0006): a stencil of this catalog
     * comes from this release, one of another catalog from that catalog's release in [scope]. The
     * release check keeps instances that do not fit out of a release, so a stencil that cannot be
     * found or that contains itself is a broken release and refuses to render; a fill or binding
     * that no longer fits renders as the expansion leaves it.
     */
    private fun expandStencils(scope: Scope, model: TemplateDocument): TemplateDocument {
        val resolver = StencilInstances.Resolver { stencilKey, catalogKey ->
            (read(scope.tenantKey, scope.releaseOf(CatalogKey.of(catalogKey)), "stencil", stencilKey) as? StencilResource)?.let {
                StencilInstances.Content(it.content, it.parameterSchema, it.readsTemplateData)
            }
        }
        val expanded = StencilInstances.expand(model, scope.release.catalogKey.value, resolver)
        expanded.findings.firstOrNull { it.problem in UNRENDERABLE }?.let { finding ->
            throw ReleaseRenderException(
                "Release ${scope.release.label()} uses stencil '${finding.stencilKey}' of catalog '${finding.catalogKey}', " +
                    if (finding.problem == StencilInstances.Problem.STENCIL_NOT_FOUND) "which it cannot supply" else "which cannot be expanded (${finding.problem})",
            )
        }
        return expanded.document
    }

    /**
     * The theme cascade, read from releases: the variant's own theme, then the template's, then the
     * tenant default. Returns the theme's catalog alongside it, because unqualified font references
     * resolve in the catalog of the theme that styles the document.
     */
    private fun resolveTheme(scope: Scope, tenant: Tenant, template: TemplateResource, model: TemplateDocument): Pair<CatalogKey?, ThemeResource?> {
        val ref = model.themeRef
        val (themeKey, themeCatalog) = when {
            ref is ThemeRefOverride -> ref.themeKey to ref.catalogKey?.let(CatalogKey::of)
            template.themeKey != null -> template.themeKey!! to template.themeCatalogKey?.let(CatalogKey::of)
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
     * does not resolves in this release first and then in the other catalogs' latest releases, in
     * catalog order: an unqualified reference used to resolve tenant-wide.
     */
    private fun assetResolver(scope: Scope): AssetResolver = AssetResolver { assetId, catalogKey ->
        val candidates = if (catalogKey != null) {
            listOf(scope.releaseOf(CatalogKey.of(catalogKey)))
        } else {
            listOf(scope.release) + scope.otherCatalogs()
        }
        candidates.firstNotNullOfOrNull { release ->
            (read(scope.tenantKey, release, "image", assetId) as? ImageResource)?.let { image ->
                binary(scope.tenantKey, image.contentHash)?.let { AssetResolution(it, image.mediaType) }
            }
        }
    }

    /**
     * Font faces by family, nearest weight first, bytes by content hash. The hash is the one the bytes
     * were looked up by in the content-addressed store, so it is exact and spares the font cache
     * hashing them again.
     */
    private fun fontResolver(scope: Scope, owningCatalog: CatalogKey): FontFamilyResolver = FontFamilyResolver { catalogKey, slug, weight, italic ->
        val catalog = catalogKey?.let(CatalogKey::of) ?: owningCatalog
        val family = read(scope.tenantKey, scope.releaseOf(catalog), "font", slug) as? FontResource
            ?: return@FontFamilyResolver null
        val face = pickNearestFace(family.variants, { it.weight }, { it.italic }, weight, italic)
            ?: return@FontFamilyResolver null
        binary(scope.tenantKey, face.contentHash)?.let { FontFaceBytes(it, face.contentHash) }
    }

    /** The identity of [templateKey] in [release], from its entries; null when the release has no such template. */
    fun templateResourceId(tenantKey: TenantKey, release: ReleaseRef, templateKey: String): java.util.UUID? = jdbi.withHandle<java.util.UUID?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT resource_id FROM release_entries
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :version
              AND resource_type = 'template' AND resource_key = :key
            """,
        )
            .bind("t", tenantKey)
            .bind("c", release.catalogKey)
            .bind("version", release.version)
            .bind("key", templateKey)
            .mapTo(java.util.UUID::class.java)
            .findOne()
            .orElse(null)
    }

    /** [templateKey] as [release] holds it, from the same cache rendering reads, or null when it holds none. */
    fun template(tenantKey: TenantKey, release: ReleaseRef, templateKey: String): TemplateResource? = read(tenantKey, release, "template", templateKey) as? TemplateResource

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
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :version
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

    /**
     * Where a catalog's resources come from while rendering one release: its own catalog from that
     * release, every other catalog from the release it is pinned to (contract ADR 0003). A pin is
     * this release's own, else that of a release it pins, nearest first, for a catalog only a
     * dependency depends on. A catalog no release pins, which only a release cut before 2.0 has,
     * comes from the highest release [environment] serves of it, else from its latest release that kept its
     * content. Each catalog is looked up once, so one render reads one release of it however many
     * references it follows.
     */
    private inner class Scope(val tenantKey: TenantKey, val release: ReleaseRef, val environment: EnvironmentKey?) {
        private val latest = mutableMapOf<CatalogKey, ReleaseRef>()

        private val pins: Map<CatalogKey, Pair<String, String>> by lazy {
            jdbi.withHandle<Map<CatalogKey, Pair<String, String>>, Exception> { handle ->
                val found = LinkedHashMap<CatalogKey, Pair<String, String>>()
                val visited = mutableSetOf(release)
                val queue = ArrayDeque(listOf(release))
                while (queue.isNotEmpty()) {
                    val from = queue.removeFirst()
                    for (pin in dependencyStore.releasePins(handle, tenantKey, from.catalogKey, from.version)) {
                        val version = pin.releaseVersion ?: continue
                        // A dependency's key is its key in this tenant: a declaration names a catalog the tenant has.
                        val catalog = CatalogKey.of(pin.catalogKey)
                        if (catalog == release.catalogKey) continue
                        found.putIfAbsent(catalog, version to pin.releaseFingerprint!!)
                        ReleaseRef(catalog, version).takeIf(visited::add)?.let(queue::addLast)
                    }
                }
                found
            }
        }

        fun releaseOf(catalog: CatalogKey): ReleaseRef = if (catalog == release.catalogKey) {
            release
        } else {
            latest.getOrPut(catalog) {
                pins[catalog]?.let { (version, fingerprint) -> pinned(catalog, version, fingerprint) } ?: unpinned(catalog)
            }
        }

        /** The pinned release, refused unless the tenant has it with its content and the fingerprint the pin recorded. */
        private fun pinned(catalog: CatalogKey, version: String, fingerprint: String): ReleaseRef {
            val stored = jdbi.withHandle<String?, Exception> { handle ->
                handle.createQuery(
                    "SELECT fingerprint FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v AND content_retained",
                )
                    .bind("t", tenantKey)
                    .bind("c", catalog)
                    .bind("v", version)
                    .mapTo(String::class.java)
                    .findOne()
                    .orElse(null)
            }
            if (stored != fingerprint) {
                throw ReleaseRenderException(
                    "Release ${release.label()} is pinned to ${catalog.value}@$version ($fingerprint), " +
                        if (stored == null) "which this tenant does not have with its content" else "but this tenant has that release as $stored",
                )
            }
            return ReleaseRef(catalog, version)
        }

        private fun unpinned(catalog: CatalogKey): ReleaseRef {
            val version = jdbi.withHandle<String?, Exception> { handle ->
                environment?.let { deployedRelease(handle, it, catalog) } ?: entryStore.latestRetainedRelease(handle, tenantKey, catalog)
            }
                ?: throw ReleaseRenderException(
                    "Release ${release.label()} uses catalog '${catalog.value}', which has no release that kept its content",
                )
            return ReleaseRef(catalog, version)
        }

        /** The highest release of [catalog] [environment] serves, as generation naming it would pick. */
        private fun deployedRelease(handle: Handle, environment: EnvironmentKey, catalog: CatalogKey): String? = handle
            .createQuery(
                """
                SELECT r.version FROM environment_catalog_deployments d
                JOIN catalog_releases r ON r.tenant_key = d.tenant_key AND r.catalog_id = d.catalog_id AND r.version = d.version
                WHERE d.tenant_key = :t AND d.environment_key = :e AND d.catalog_id = ${catalogAt("t", "c")} AND r.content_retained
                $LATEST_RELEASE_ORDER LIMIT 1
                """,
            )
            .bind("t", tenantKey)
            .bind("e", environment)
            .bind("c", catalog)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)

        /** Every other catalog of the tenant, in catalog order: its pinned release, else its latest. */
        fun otherCatalogs(): List<ReleaseRef> = jdbi.withHandle<Map<CatalogKey, String>, Exception> { handle -> entryStore.latestRetainedReleases(handle, tenantKey) }
            .filterKeys { it != release.catalogKey }
            .entries.sortedBy { it.key.value }
            .map { (catalog, version) -> if (catalog in pins) releaseOf(catalog) else ReleaseRef(catalog, version).also { latest.putIfAbsent(catalog, it) } }
    }

    private companion object {
        const val MAX_RESOURCES = 2_000L
        const val MAX_BINARY_BYTES = 64L * 1024 * 1024
        val UNRENDERABLE = setOf(
            StencilInstances.Problem.STENCIL_NOT_FOUND,
            StencilInstances.Problem.RECURSION,
            StencilInstances.Problem.DEPTH_EXCEEDED,
        )
    }
}

private fun ReleaseRef.label(): String = "${catalogKey.value}@$version"
