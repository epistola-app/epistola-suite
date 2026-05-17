package app.epistola.suite.catalog

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.catalog.commands.InvalidCatalogException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Resolves transitive dependencies for a set of catalog resources.
 * Scans template and stencil content for references to themes, stencils, attributes, and assets,
 * then recursively scans newly-discovered stencils for their own dependencies.
 *
 * @throws InvalidCatalogException if any referenced dependency is missing from the manifest
 */
@Component
class DependencyResolver(
    private val catalogClient: CatalogClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun resolve(
        selected: List<ResourceEntry>,
        manifest: CatalogManifest,
        sourceUrl: String,
        authType: AuthType,
        credential: String?,
    ): List<ResourceEntry> {
        val allByKey = manifest.resources.associateBy { "${it.type}:${it.slug}" }
        val result = selected.associateByTo(mutableMapOf()) { "${it.type}:${it.slug}" }
        val scanned = mutableSetOf<String>()
        val missing = mutableSetOf<String>()

        // Templates and stencils have content to walk; attributes carry a
        // (potentially same-catalog) code-list reference; themes carry a
        // (potentially same-catalog) fontFamily reference. Scan all on the
        // first pass; recursion revisits stencils, attributes and themes
        // brought in transitively (a pulled theme can pull a same-catalog font).
        var toScan = selected.filter {
            it.type == "template" || it.type == "stencil" || it.type == "attribute" || it.type == "theme"
        }

        while (toScan.isNotEmpty()) {
            val deps = mutableListOf<DependencyScanner.Dependencies>()

            for (entry in toScan) {
                val key = "${entry.type}:${entry.slug}"
                if (key in scanned) continue
                scanned += key

                val detail = catalogClient.fetchResourceDetail(entry.detailUrl, sourceUrl, authType, credential)
                when (val resource = detail.resource) {
                    is TemplateResource -> {
                        // Resource-level theme reference (themeId field)
                        if (resource.themeId != null) {
                            deps += DependencyScanner.Dependencies(themeRefs = setOf(resource.themeId!!))
                        }
                        // Variant attribute keys may be catalog-qualified
                        // (`"<catalogKey>.<slug>"`). Bare slugs resolve
                        // within this manifest; qualified keys pointing at
                        // another catalog are cross-catalog references —
                        // their resolution is the consumer-tenant's
                        // problem, not ours, and they should not be looked
                        // up in `allByKey`.
                        val variantAttrs = resource.variants
                            .flatMap { (it.attributes ?: emptyMap()).keys }
                            .filter { '.' !in it }
                            .toSet()
                        deps += DependencyScanner.scan(resource.templateModel, variantAttrs)
                        resource.variants.mapNotNull { it.templateModel }.forEach { deps += DependencyScanner.scan(it) }
                    }
                    is StencilResource -> deps += DependencyScanner.scan(resource.content)
                    is AttributeResource -> {
                        // Same-catalog bindings are auto-included via the
                        // resolver; cross-catalog ones (`catalogKey != null`)
                        // are declared on `manifest.dependencies` and checked
                        // at install-time, not pulled in here.
                        val binding = resource.codeListBinding
                        if (binding != null && binding.catalogKey == null) {
                            deps += DependencyScanner.Dependencies(codeListRefs = setOf(binding.slug))
                        }
                    }
                    is ThemeResource -> {
                        // A theme's documentStyles / blockStylePresets can carry
                        // a `fontFamily` ref. Same-catalog refs (no explicit
                        // catalogKey) are auto-pulled; cross-catalog ones are
                        // declared on manifest.dependencies and checked at
                        // install-time.
                        val sameCatalogFonts = DependencyScanner
                            .themeFontRefs(resource.documentStyles, resource.blockStylePresets)
                            .filter { it.catalogKey == null }
                            .mapTo(mutableSetOf()) { it.slug }
                        if (sameCatalogFonts.isNotEmpty()) {
                            deps += DependencyScanner.Dependencies(fontRefs = sameCatalogFonts)
                        }
                    }
                    is FontResource -> {
                        // A `FontResource` carries no binding to scan: its
                        // faces are same-catalog assets, which are declared
                        // and pulled in as ordinary asset dependencies.
                    }
                    else -> {}
                }
            }

            if (deps.isEmpty()) break

            val merged = DependencyScanner.merge(*deps.toTypedArray())
            val newEntries = mutableListOf<ResourceEntry>()

            fun resolve(type: String, slug: String) {
                val key = "$type:$slug"
                if (key in result) return
                val e = allByKey[key]
                if (e == null) {
                    missing += key
                } else if (result.putIfAbsent(key, e) == null) {
                    newEntries += e
                }
            }

            merged.themeRefs.forEach { resolve("theme", it) }
            merged.stencilRefs.forEach { resolve("stencil", it) }
            merged.attributeKeys.forEach { resolve("attribute", it) }
            merged.assetRefs.forEach { resolve("asset", it) }
            merged.codeListRefs.forEach { resolve("codeList", it) }
            merged.fontRefs.forEach { resolve("font", it) }

            // Continue scanning anything newly discovered that can pull in
            // further dependencies: stencils (template-model content) and
            // attributes (newly discovered via template `variants[].attributes`).
            toScan = newEntries.filter { it.type == "stencil" || it.type == "attribute" || it.type == "theme" }
        }

        if (missing.isNotEmpty()) {
            throw InvalidCatalogException(
                "Catalog is incomplete — the following dependencies are referenced but not included: ${missing.sorted().joinToString(", ")}",
            )
        }

        if (result.size > selected.size) {
            val added = result.keys - selected.map { "${it.type}:${it.slug}" }.toSet()
            logger.info("Auto-including {} dependencies: {}", added.size, added)
        }

        return result.values.toList()
    }
}
