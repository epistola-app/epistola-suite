// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogThemeRef
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.merge.CatalogSetting
import app.epistola.suite.common.ids.CatalogKey

/**
 * One change to what a draft holds beside its resources, against the release it is based on: the
 * content a release's content hash covers outside its resources (ADR 0034, contract ADR 0003) —
 * each catalog it declares it uses, its default theme, its presets and its license. The catalog's
 * presentation (name, description, keywords, presentation, attributes) is not content, so it is
 * never a change of a release.
 *
 * A catalog setting carries no ready mark ([app.epistola.suite.catalog.ready.ReadyMarks]): choosing a
 * dependency, a default theme, presets or a license is one deliberate act, with nothing half-done to
 * review, so a change of one never waits on a mark and never blocks a release.
 *
 * @property entry `type/key` as a merge names the entry: `catalog/defaultTheme`, `catalog/presets`,
 *   `catalog/license`, or `catalogDependency/<alias>` for a dependency.
 * @property state [CatalogResourceState.NEW] for a setting the base did not have (a dependency
 *   added, a default theme or license where there was none, presets where there were none),
 *   [CatalogResourceState.REMOVED] for one the draft no longer has, else
 *   [CatalogResourceState.MODIFIED].
 * @property name what people call it: `Default theme`, `Presets`, `License`, or the name of the
 *   catalog a dependency names (its alias when the tenant no longer has it).
 * @property lock for a dependency, what the draft renders it with; null when it was removed, and
 *   for the other settings.
 * @property baseLock for a dependency, what the base pinned; null when it was added, and for the
 *   other settings.
 */
data class CatalogSettingChange(
    val entry: String,
    val state: CatalogResourceState,
    val name: String,
    val lock: DependencyLock? = null,
    val baseLock: DependencyLock? = null,
) {
    /** The entry type: [CatalogSetting.TYPE] or [CatalogSetting.DEPENDENCY_TYPE]. */
    val type: String get() = entry.substringBefore('/')

    /** The entry key: the setting's, or the dependency's alias. */
    val key: String get() = entry.substringAfter('/')

    val isDependency: Boolean get() = type == CatalogSetting.DEPENDENCY_TYPE
}

/**
 * What a declared dependency renders with: a release, by number, or in a draft a draft of that
 * catalog, by name; both null while nothing is chosen.
 */
data class DependencyLock(val releaseVersion: String?, val draftName: String? = null)

/**
 * The catalog-level content of one side of a comparison: the catalog details a version holds
 * ([CatalogInfo]: its default theme, presets and license; null for nothing, before a first release)
 * and the catalogs it declares it uses, with, for a declaration that names a draft of its catalog,
 * the release that draft is based on ([namedDraftBases], by alias).
 */
data class CatalogSettings(
    val info: CatalogInfo?,
    val dependencies: List<CatalogDependencyDeclaration>,
    val namedDraftBases: Map<String, String> = emptyMap(),
) {
    companion object {
        /** No release: no default theme, presets, license or dependency. */
        val NONE = CatalogSettings(info = null, dependencies = emptyList())
    }
}

/**
 * What a draft changed beside its resources against its base ([CatalogSettingChange]), compared as
 * the content hash compares it (contract `CatalogCanonicalizer`, V6): the default theme by theme and
 * alias, the own catalog's alias and none meaning the same; the presets by key, name and
 * description, in any order; the license whole; each dependency by its alias and the release it
 * pins. A dependency's registry and namespace are its name, not content (ADR 0034), so renaming or
 * mirroring one is no change. A pin is compared by number and fingerprint, so moving it to another
 * release is a change. A declaration that names a draft of the catalog is compared as the release
 * it pins when that draft has nothing to publish, the draft's base (as `ReleaseCatalogDraft` pins
 * it): what the named draft changed itself is the other catalog's change, published along
 * ([app.epistola.suite.catalog.dependencies.GetCatalogBorrowing]), not one of this catalog.
 */
object CatalogSettingDiff {
    /** The entry of the license, which a merge does not take (the license is catalog-wide here). */
    const val LICENSE: String = "${CatalogSetting.TYPE}/license"

    private const val DEFAULT_THEME = "${CatalogSetting.TYPE}/defaultTheme"
    private const val PRESETS = "${CatalogSetting.TYPE}/presets"

    /**
     * The changes [draft] holds against [base], ordered by entry; [nameOf] names the catalog of a
     * dependency alias, null when the tenant has none by it.
     */
    fun between(catalogKey: CatalogKey, base: CatalogSettings, draft: CatalogSettings, nameOf: (alias: String) -> String?): List<CatalogSettingChange> {
        val changes = mutableListOf<CatalogSettingChange>()
        fun setting(entry: String, name: String, was: Any?, now: Any?) {
            stateOf(was, now)?.let { changes += CatalogSettingChange(entry, it, name) }
        }
        setting(DEFAULT_THEME, "Default theme", base.info?.defaultTheme.normalized(catalogKey), draft.info?.defaultTheme.normalized(catalogKey))
        setting(LICENSE, "License", base.info?.license, draft.info?.license)
        setting(PRESETS, "Presets", base.info.presets(), draft.info.presets())

        val was = base.dependencies.associateBy { it.alias }
        val now = draft.dependencies.associateBy { it.alias }
        for (alias in (was.keys + now.keys).sorted()) {
            val before = was[alias]
            val after = now[alias]
            val state = when {
                before == null -> CatalogResourceState.NEW
                after == null -> CatalogResourceState.REMOVED
                samePin(before, after, draft.namedDraftBases[alias]) -> null
                else -> CatalogResourceState.MODIFIED
            } ?: continue
            changes += CatalogSettingChange(
                entry = CatalogSetting.dependencyKey(alias),
                state = state,
                name = nameOf(alias) ?: alias,
                lock = after?.let { DependencyLock(it.releaseVersion, it.draftName) },
                baseLock = before?.let { DependencyLock(it.releaseVersion, it.draftName) },
            )
        }
        return changes.sortedBy { it.entry }
    }

    /** Null when unchanged; a value counts as unset when it is null or an empty list. */
    private fun stateOf(was: Any?, now: Any?): CatalogResourceState? {
        fun unset(value: Any?) = value == null || (value is List<*> && value.isEmpty())
        return when {
            was == now -> null
            unset(was) -> CatalogResourceState.NEW
            unset(now) -> CatalogResourceState.REMOVED
            else -> CatalogResourceState.MODIFIED
        }
    }

    /** The theme and the catalog holding it, the own catalog named whether or not the reference names it. */
    private fun CatalogThemeRef?.normalized(catalogKey: CatalogKey): Pair<String, String>? = this?.let { (it.catalogKey ?: catalogKey.value) to it.key }

    private fun CatalogInfo?.presets(): List<Triple<String, String, String?>> = this?.presets.orEmpty().map { Triple(it.key, it.name, it.description) }.sortedBy { it.first }

    /**
     * Whether [after] pins what [before] pinned: the same release by number and fingerprint, or, for
     * a declaration naming a draft, that draft's base ([namedDraftBase]) is the release [before] pinned.
     */
    private fun samePin(before: CatalogDependencyDeclaration, after: CatalogDependencyDeclaration, namedDraftBase: String?): Boolean = if (after.namesDraft) {
        namedDraftBase != null && namedDraftBase == before.releaseVersion
    } else {
        after.releaseVersion == before.releaseVersion && after.releaseFingerprint == before.releaseFingerprint
    }
}
