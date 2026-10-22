// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.InstalledResource
import app.epistola.suite.catalog.RESOURCE_INSTALL_ORDER
import app.epistola.suite.catalog.commands.CatalogResourceInstaller
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.graph.TenantResourceGraphBuilder
import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
import app.epistola.suite.catalog.releases.SetCatalogPresets
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.JdbiException
import org.springframework.stereotype.Component

/** What a merge wrote into a draft, each as `type/key`. */
data class DraftWrites(
    /** Written as the release has it: resources and settings. */
    val written: List<String>,
    /** Removed, because the release removed it. */
    val removed: List<String>,
)

/**
 * Writes what a merge decided ([MergeAction]) into the draft the transaction targets: a resource as
 * the release holds it ([CatalogResourceInstaller], the import's own code), marked ready as released
 * content; a removal, refused while something still uses it; a catalog setting.
 *
 * Resources are written first, in dependency order, then removals (whose use is read after the
 * writes, since what was taken refers to what the release has), then settings (a default theme may
 * name a theme just taken).
 */
@Component
class DraftResourceWriter(
    private val jdbi: Jdbi,
    private val installer: CatalogResourceInstaller,
    private val readyMarks: ReadyMarks,
    private val analyzer: CatalogUpgradeAnalyzer,
    private val graph: TenantResourceGraphBuilder,
    private val dependencyStore: CatalogDependencyStore,
) {

    /**
     * Applies [actions] (by `type/key`) to the draft. [release] is the release they come from and
     * [draftLabel] what people call the draft, for refusals.
     *
     * @throws MergeRefusedException when a resource cannot be written, or a removal is still used.
     */
    fun apply(tenantKey: TenantKey, catalogKey: CatalogKey, release: String, draftLabel: String, actions: Map<String, MergeAction>): DraftWrites = CatalogImportContext.runAsImport {
        val writes = actions.mapNotNull { (key, action) -> (action as? MergeAction.Write)?.let { key to it.value } }
        val resources = writes.mapNotNull { (key, value) -> (value as? MergeValue.Resource)?.let { key to it } }
            .sortedBy { (_, value) -> RESOURCE_INSTALL_ORDER[value.resource.type] ?: 99 }
        val settings = writes.mapNotNull { (key, value) -> (value as? MergeValue.Setting)?.let { key to it.setting } }
        val removed = actions.filterValues { it == MergeAction.Remove }.keys.toList()
        val (droppedDependencies, removals) = removed.partition(CatalogSetting::isSettingKey)
        val (dependencies, otherSettings) = settings.partition { (_, setting) -> setting is CatalogSetting.Dependency }

        resources.forEach { (key, value) -> write(tenantKey, catalogKey, release, key, value) }
        remove(tenantKey, catalogKey, release, draftLabel, removals)
        // Declarations before the default theme, which may name a theme of a catalog they declare.
        writeDependencies(tenantKey, catalogKey, release, dependencies.map { it.second as CatalogSetting.Dependency }, droppedDependencies)
        otherSettings.forEach { (key, setting) -> writeSetting(tenantKey, catalogKey, release, key, setting) }

        DraftWrites(written = resources.map { it.first } + settings.map { it.first }, removed = removed)
    }

    /**
     * The draft's dependency declarations with [taken] written as the release declares them (in place
     * of the draft's declaration of that catalog, or added after the others) and [dropped]
     * (`catalogDependency/<catalogKey>`) removed, in one replacement, so a cycle is refused once.
     */
    private fun writeDependencies(tenantKey: TenantKey, catalogKey: CatalogKey, release: String, taken: List<CatalogSetting.Dependency>, dropped: List<String>) {
        if (taken.isEmpty() && dropped.isEmpty()) return
        val gone = dropped.map { it.substringAfter('/') }.toSet()
        val byCatalog = taken.associate { it.declaration.catalogKey to it.declaration }
        try {
            jdbi.useHandle<Exception> { handle ->
                val current = dependencyStore.declarations(handle, tenantKey, catalogKey)
                val kept = current.filter { it.catalogKey !in gone }.map { byCatalog[it.catalogKey] ?: it }
                val added = taken.map { it.declaration }.filter { declaration -> current.none { it.catalogKey == declaration.catalogKey } }
                dependencyStore.replace(handle, tenantKey, catalogKey, kept + added)
            }
        } catch (e: ValidationException) {
            throw MergeRefusedException(catalogKey, "Could not take the catalogs $release uses: ${e.message}")
        }
    }

    /** Writes one resource as [value] holds it, and marks it ready. */
    private fun write(tenantKey: TenantKey, catalogKey: CatalogKey, release: String, key: String, value: MergeValue.Resource) {
        try {
            installer.install(tenantKey, catalogKey, value.resource, release, value.binaries::get)
        } catch (e: MergeRefusedException) {
            throw e
        } catch (e: RuntimeException) {
            throw MergeRefusedException(catalogKey, "Could not take ${value.resource.type} '${value.resource.key}': ${e.message}")
        }
        jdbi.useHandle<Exception> { handle ->
            readyMarks.resourceId(handle, tenantKey, catalogKey, value.resource.type, value.resource.key)?.let { readyMarks.mark(handle, tenantKey, it) }
        }
        check(key == "${value.resource.type}/${value.resource.key}") { "$key holds ${value.resource.type}/${value.resource.key}" }
    }

    /**
     * Removes [keys] (`type/key`), refused while something uses one: another catalog
     * ([CatalogUpgradeAnalyzer.findConflicts]), or the draft itself, which that check leaves out — a
     * reference points at an identity, so removing the row would leave it pointing at nothing
     * instead of failing.
     */
    private fun remove(tenantKey: TenantKey, catalogKey: CatalogKey, release: String, draftLabel: String, keys: List<String>) {
        if (keys.isEmpty()) return
        val removed = keys.map { it.substringBefore('/') to it.substringAfter('/') }
        val stale = removed.map { (type, key) -> InstalledResource(type, key) }
        val inUse = analyzer.findConflicts(tenantKey, catalogKey, stale)
        if (inUse.isNotEmpty()) throw MergeRefusedException(catalogKey, "$release removes what is still used: ${inUse.joinToString("; ")}")
        val gone = removed.toSet()
        val users = jdbi.withHandle<_, Exception> { graph.buildOn(it, tenantKey, includeHistory = false) }.edges.filter { edge ->
            val target = edge.target ?: return@filter false
            target.catalogKey == catalogKey.value &&
                (target.type.wireName to target.key) in gone &&
                edge.source.catalogKey == catalogKey.value &&
                (edge.source.type.wireName to edge.source.key) !in gone
        }
        if (users.isNotEmpty()) {
            throw stillUsed(catalogKey, release, draftLabel, users.map { "${it.target!!.type.wireName} '${it.target.key}' (used by ${it.source.type.wireName} '${it.source.key}')" }.distinct())
        }
        try {
            analyzer.removeStale(tenantKey, catalogKey, stale)
        } catch (e: JdbiException) {
            // A reference the database itself refuses to leave behind (an attribute's code list).
            throw stillUsed(catalogKey, release, draftLabel, removed.map { (type, key) -> "$type '$key'" })
        }
    }

    /** Writes one catalog setting as the release has it. */
    private fun writeSetting(tenantKey: TenantKey, catalogKey: CatalogKey, release: String, key: String, setting: CatalogSetting) {
        try {
            when (setting) {
                // The merge's declarations stand as merged: a theme whose catalog they lack is refused after.
                is CatalogSetting.DefaultTheme -> SetCatalogDefaultTheme(tenantKey, catalogKey, setting.theme, declaresCatalog = false).execute()

                is CatalogSetting.Presets -> SetCatalogPresets(tenantKey, catalogKey, setting.presets).execute()

                is CatalogSetting.Dependency -> error("dependencies are written together")
            }
        } catch (e: ValidationException) {
            throw MergeRefusedException(catalogKey, "Could not take ${setting.label.lowercase()} ($key) of $release: ${e.message}")
        }
    }

    private fun stillUsed(catalogKey: CatalogKey, release: String, draftLabel: String, what: List<String>) = MergeRefusedException(
        catalogKey,
        "$release removes ${what.joinToString()}, which $draftLabel still uses. Keep it, or stop using it first.",
    )
}
