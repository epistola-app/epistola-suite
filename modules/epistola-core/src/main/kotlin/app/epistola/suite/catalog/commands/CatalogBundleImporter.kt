// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogBundle
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Imports a catalog bundle (a fat export, [CatalogBundle]) for [ImportCatalogZipHandler]. */
@Component
class CatalogBundleImporter(
    private val objectMapper: ObjectMapper,
    private val sizeLimits: CatalogSizeLimits,
) {
    /**
     * A fat export: the catalogs it uses first, then the catalog itself as [command] asks.
     *
     * Every bundled dependency is checked before anything is written. One this installation already
     * has at that release is left alone; one it lacks is installed as a subscribed catalog. One it has
     * at another release, or under another name, is a conflict: installing the bundled release would
     * move what that catalog is here for everything else using it, so the import is refused, naming
     * each, and nothing changes. A dependency that fails to install refuses the whole import; it runs
     * in one transaction, so nothing of it stays.
     */
    fun import(command: ImportCatalogZip, importArchive: (ImportCatalogZip) -> ImportCatalogZipResult): ImportCatalogZipResult {
        val bundle = try {
            CatalogBundle.read(command.zipBytes, objectMapper, sizeLimits.maxDecompressedSize.toBytes())
        } catch (e: IllegalArgumentException) {
            throw CatalogBundleException(e.message ?: "Not a catalog bundle")
        }
        val missing = mutableListOf<CatalogBundle.Entry>()
        val present = mutableListOf<CatalogBundle.Entry>()
        val conflicts = mutableListOf<String>()
        for (dependency in bundle.dependencies) {
            val key = CatalogKey.of(dependency.catalogKey)
            val catalog = GetCatalog(command.tenantKey, key).query()
            when {
                catalog == null -> missing += dependency

                catalog.registry != dependency.registry || catalog.namespace != dependency.namespace ->
                    conflicts += "${dependency.catalogKey} here is ${catalog.registry}/${catalog.namespace}/${catalog.id.value}, not ${dependency.identity}"

                ListCatalogReleases(command.tenantKey, key).query().any { it.version == dependency.version && it.retained } ||
                    catalog.installedReleaseVersion == dependency.version -> present += dependency

                else -> conflicts += "${dependency.catalogKey} is here at " + (catalog.installedReleaseVersion?.let { "v$it" } ?: catalog.releasedVersion?.let { "v$it" } ?: "no release") +
                    ", and ${bundle.root.catalogKey} needs v${dependency.version}"
            }
        }
        if (conflicts.isNotEmpty()) {
            throw CatalogBundleException("Cannot import ${bundle.root.catalogKey} with what it uses: " + conflicts.joinToString("; ") + ".")
        }
        for (dependency in missing) {
            val installed = importArchive(ImportCatalogZip(command.tenantKey, dependency.archive, CatalogType.SUBSCRIBED, confirmMigration = true))
            if (installed.aborted) {
                throw CatalogBundleException(
                    "${dependency.catalogKey} v${dependency.version} could not be installed: " +
                        installed.results.filter { it.status == InstallStatus.FAILED }.joinToString { "${it.type} '${it.slug}': ${it.errorMessage}" },
                )
            }
        }
        return importArchive(command.copy(zipBytes = bundle.root.archive)).copy(
            installedDependencies = missing.map { "${it.catalogKey} v${it.version}" },
            presentDependencies = present.map { "${it.catalogKey} v${it.version}" },
        )
    }
}
