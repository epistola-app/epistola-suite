package app.epistola.suite.catalog

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceEntry
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
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

        var toScan = selected.filter { it.type == "template" || it.type == "stencil" }

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
                        val variantAttrs = resource.variants
                            .flatMap { (it.attributes ?: emptyMap()).keys }
                            .toSet()
                        deps += DependencyScanner.scan(resource.templateModel, variantAttrs)
                        resource.variants.mapNotNull { it.templateModel }.forEach { deps += DependencyScanner.scan(it) }
                    }
                    is StencilResource -> deps += DependencyScanner.scan(resource.content)
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

            toScan = newEntries.filter { it.type == "stencil" }
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
