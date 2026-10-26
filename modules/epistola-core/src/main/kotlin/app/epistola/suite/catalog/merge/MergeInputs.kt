// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.releases.CatalogDraft
import app.epistola.suite.catalog.releases.CatalogDrafts
import app.epistola.suite.catalog.releases.CatalogPreset
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.releases.ReleaseLineage
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * A setting of a catalog version that a release holds beside its resources (contract ADR 0007 and
 * 0003), merged as one entry like a resource: `catalog/<key>` for the default theme and the presets,
 * `catalogDependency/<catalogKey>` for each catalog it declares it uses. The catalog's name,
 * description and presentation are catalog-wide, not a version's, so they are not merged.
 */
sealed interface CatalogSetting {
    /** The entry type it is merged under: [TYPE], or [DEPENDENCY_TYPE] for a dependency. */
    val type: String get() = TYPE

    /** Its key under [type]. */
    val key: String

    /** `type/key`, as a merge names it. */
    val contentKey: String get() = "$type/$key"

    /** What people call it. */
    val label: String

    /** Its value in a fixed shape and order, so that equal settings fingerprint equally on every side. */
    val canonical: Any?

    /**
     * The theme a template that names none renders with; null for none. As content holds it: by the
     * alias of its catalog (ADR 0034, section 9), resolved when it is written.
     */
    data class DefaultTheme(val theme: ThemeByAlias?) : CatalogSetting {
        override val key get() = "defaultTheme"
        override val label get() = "Default theme"
        override val canonical get() = theme?.let { mapOf("catalogKey" to it.catalogKey.value, "key" to it.themeKey.value) }
    }

    /** The block style presets the version declares. */
    data class Presets(val presets: List<CatalogPreset>) : CatalogSetting {
        override val key get() = "presets"
        override val label get() = "Presets"
        override val canonical get() = presets.sortedBy { it.key }.map { mapOf("key" to it.key, "name" to it.name, "description" to it.description) }
    }

    /**
     * One catalog the version uses, as it declares it: the release it pins, or, in a draft, the
     * draft of that catalog it names. Keyed by the catalog's key, which is unique in a tenant (a
     * merge entry's key holds at most 50 characters, which a catalog key always fits and its full
     * name need not); its registry and namespace are part of its value.
     */
    data class Dependency(val declaration: CatalogDependencyDeclaration) : CatalogSetting {
        override val type get() = DEPENDENCY_TYPE
        override val key get() = declaration.catalogKey
        override val label get() = declaration.catalogKey
        override val canonical get() = mapOf(
            "registry" to declaration.registry,
            "namespace" to declaration.namespace,
            "catalogKey" to declaration.catalogKey,
            "releaseVersion" to declaration.releaseVersion,
            "releaseFingerprint" to declaration.releaseFingerprint,
        ) + (declaration.draftId?.let { mapOf("draft" to it) } ?: emptyMap())
    }

    companion object {
        /** The entry type the default theme and the presets are merged under. */
        const val TYPE: String = "catalog"

        /** The entry type each declared dependency is merged under, keyed by the catalog's key. */
        const val DEPENDENCY_TYPE: String = "catalogDependency"

        /**
         * The one entry every dependency declaration was merged as before each catalog was an entry of
         * its own: a baseline recorded then still names it, and [completeBaseline] replaces it.
         */
        const val LEGACY_DEPENDENCIES: String = "catalog/dependencies"

        /** Whether [type] is that of a setting entry rather than a resource. */
        fun isSettingType(type: String): Boolean = type == TYPE || type == DEPENDENCY_TYPE

        /** Whether [contentKey] (`type/key`) names a setting entry rather than a resource. */
        fun isSettingKey(contentKey: String): Boolean = isSettingType(contentKey.substringBefore('/'))

        /** The entry key of the dependency on [catalogKey]. */
        fun dependencyKey(catalogKey: String): String = "$DEPENDENCY_TYPE/$catalogKey"

        /** Every setting as a version that set none holds it: no default theme, no presets, no dependency. */
        val UNSET: List<CatalogSetting> = listOf(DefaultTheme(null), Presets(emptyList()))

        /**
         * The settings [content] of [catalogKey] holds: its default theme and presets, set or not, and
         * one entry per dependency it declares, from [declarations] when given (a draft's own, which
         * may name a draft of another catalog; the wire form cannot), else from the content.
         */
        fun of(catalogKey: CatalogKey, content: CatalogContent, declarations: List<CatalogDependencyDeclaration>? = null): List<CatalogSetting> = listOf(
            DefaultTheme(content.catalog.defaultTheme?.let { ThemeByAlias(ThemeKey.of(it.key), it.catalogKey?.let(CatalogKey::of) ?: catalogKey) }),
            Presets(content.catalog.presets.map { CatalogPreset(it.key, it.name, it.description) }),
        ) + (declarations ?: content.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of)).distinctBy { it.catalogKey }.map(::Dependency)

        /**
         * [held], a baseline a draft recorded (what it held of a release, by entry), completed from
         * [base], the same release as released: a setting entry it does not name (one recorded before
         * settings were merged) is the base's, and one recorded while every dependency was a single
         * entry ([LEGACY_DEPENDENCIES]) gets the base's dependency entries in place of it.
         */
        fun completeBaseline(held: Map<String, String>, base: Map<String, String>): Map<String, String> {
            val beforeEachDependency = LEGACY_DEPENDENCIES in held || held.keys.none(::isSettingKey)
            val filled = base.filterKeys { key ->
                when (key.substringBefore('/')) {
                    TYPE -> key !in held
                    DEPENDENCY_TYPE -> beforeEachDependency
                    else -> false
                }
            }
            return held - LEGACY_DEPENDENCIES + filled
        }
    }
}

/**
 * One side of a merge: the content it holds (null for a side that has none, a merge without a
 * base) and every entry of it, resources and [CatalogSetting]s, by `type/key`.
 */
data class MergeInput(
    val content: CatalogContent?,
    val values: Map<String, MergeValue>,
) {
    /** A fingerprint per entry, by one function on every side ([MergeInputReader]). */
    val fingerprints: Map<String, String> get() = values.mapValues { it.value.fingerprint }

    /** What each resource is called on this side. */
    val names: Map<String, String> get() = content?.resourceEntries.orEmpty().associate { "${it.type}/${it.key}" to it.name }

    companion object {
        val NONE = MergeInput(content = null, values = emptyMap())
    }
}

/**
 * The merge base a merge needs and this installation does not have: the release the lines descend
 * from by their recorded lineage, by its number and the fingerprint the line names it by (contract
 * ADR 0005, "The merge base travels with the release"). It is not installed, or the release installed
 * under that number is another one.
 */
data class MergeBaseRef(val version: String, val fingerprint: String)

/**
 * What merging [release] into [draft] starts from: the merge base ([mergeBase], the closest release
 * both descend from; null when none is known, or when it is [missingBase]), the draft (ours) and the
 * release (theirs).
 */
data class MergeInputs(
    val draft: CatalogDraft,
    val release: String,
    val mergeBase: String?,
    /** The releases the draft contains already ([ReleaseLineage.containedBy]). */
    val contained: Set<String>,
    val base: MergeInput,
    val ours: MergeInput,
    val theirs: MergeInput,
    /**
     * The base as the draft holds it, by entry: what the draft is compared with to say it changed
     * something. A release received from elsewhere is written into a draft as this suite holds
     * content, which need not fingerprint as its publisher's content does, so where the draft
     * recorded what it held of the base (its own base when it was put in place or copied in,
     * `catalog_versions.base_fingerprints`; a release it merged, `catalog_draft_merges.held_fingerprints`)
     * that is used, also for a release that one descends from where the two are alike. Otherwise the
     * base as released.
     */
    val heldBase: Map<String, String> = base.fingerprints,
    /** The merge base found by lineage that this installation does not have; nothing can be classified without it. */
    val missingBase: MergeBaseRef? = null,
)

/**
 * Reads [MergeInputs]. The draft's content is built from its rows; each release's from the content it
 * retained ([ReleaseContentAssembler]). All three are fingerprinted by the same function
 * ([CatalogFingerprintService.resourceFingerprints], the complete per-resource form of V5), not taken from `release_resources`: a release
 * received from a source records its publisher's digests there, which need not be what this suite
 * computes for the same content, and comparing the two reports changes nobody made.
 *
 * The merge base is found by the lineage each release records ([ReleaseLineage]: its base,
 * `catalog_versions.base_version`, and its merge parents, `catalog_release_merges`, as cut here or as a
 * received release named them), never guessed from the release numbers (contract ADR 0005): a release
 * that records none ends its line.
 */
@Component
class MergeInputReader(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val assembler: ReleaseContentAssembler,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprints: CatalogFingerprintService,
    private val dependencyStore: CatalogDependencyStore,
    private val identity: app.epistola.suite.catalog.revisions.ReleaseIdentity,
) {

    /**
     * The inputs of merging [release] into the draft the running message targets (else the catalog's
     * next version).
     *
     * @throws CatalogReleaseNotFoundException when the catalog has no such draft, or no such release.
     */
    fun read(catalog: CatalogId, release: String, draftName: String): MergeInputs {
        val draft = jdbi.withHandle<_, Exception> { drafts.draft(it, catalog) }
            ?: throw CatalogReleaseNotFoundException(catalog.key, draftName)
        val lineage = jdbi.withHandle<ReleaseLineage, Exception> { ReleaseLineage.read(it, catalog) }
        val lines = lineage.releases
        if (release !in lines) throw CatalogReleaseNotFoundException(catalog.key, release)
        // What the draft contains: its base, what it merged, and every parent of those (base and merges).
        val contained = lineage.containedBy(draft.base, draft.merged)
        val found = lineage.closestIn(release, contained)
        val installed = found?.takeIf { lines[it.version]?.let { line -> it.fingerprint == null || sameRelease(catalog, it.version, line.fingerprint, it.fingerprint) } == true }
        // Found by lineage and not here (or another release under its number): named, to get first. One
        // that is not here and whose fingerprint nobody named cannot be asked for, so no base is known.
        val missing = found?.takeIf { installed == null }?.fingerprint?.let { MergeBaseRef(found.version, it) }
        val mergeBase = installed?.version
        val base = mergeBase?.let { releaseInput(catalog, it) } ?: MergeInput.NONE
        return MergeInputs(
            draft = draft,
            release = release,
            mergeBase = mergeBase,
            contained = contained,
            base = base,
            ours = draftInput(catalog),
            theirs = releaseInput(catalog, release),
            heldBase = mergeBase?.let { heldBase(catalog, draft, it, base, lineage) }.orEmpty(),
            missingBase = missing,
        )
    }

    /**
     * Whether the release installed as [version] with [installed] is the one [expected] names: by its
     * content hash, or another algorithm's fingerprint of the same content ([ReleaseIdentity]).
     */
    private fun sameRelease(catalog: CatalogId, version: String, installed: String, expected: String): Boolean {
        if (installed == expected) return true
        return jdbi.withHandle<Boolean, Exception> { identity.isRelease(it, catalog, version, expected) }
    }

    /**
     * [MergeInputs.heldBase] of [mergeBase] in [draft]: what the draft recorded holding of it (its
     * base, or a release it merged), else of a release it holds that descends from [mergeBase], for
     * every entry the two releases have alike; else the base as released.
     */
    private fun heldBase(
        catalog: CatalogId,
        draft: CatalogDraft,
        mergeBase: String,
        base: MergeInput,
        lineage: ReleaseLineage,
    ): Map<String, String> {
        val anchors = listOfNotNull(draft.base) + draft.merged
        for (anchor in anchors) {
            if (!lineage.descendsFrom(anchor, mergeBase)) continue
            val held = recordedHeld(catalog.tenantKey, draft, anchor) ?: continue
            if (anchor == mergeBase) return CatalogSetting.completeBaseline(held, base.fingerprints)
            // What the draft holds of the anchor is what it holds of the merge base wherever the two
            // releases are alike; elsewhere the anchor changed it, and the base as released is compared.
            val anchorReleased = releaseInput(catalog, anchor).fingerprints
            val complete = CatalogSetting.completeBaseline(held, anchorReleased)
            return base.fingerprints.mapValues { (key, released) -> if (anchorReleased[key] == released) complete[key] ?: released else released }
        }
        return base.fingerprints
    }

    /** What [draft] recorded holding of [release], its own base or one it merged; null when nothing was recorded. */
    private fun recordedHeld(tenantKey: TenantKey, draft: CatalogDraft, release: String): Map<String, String>? {
        val recorded = jdbi.withHandle<String?, Exception> { handle ->
            when (release) {
                draft.base -> drafts.baseFingerprints(handle, tenantKey, draft.versionId)
                in draft.merged -> drafts.mergeHeldFingerprints(handle, tenantKey, draft.versionId, release)
                else -> null
            }
        }
        // A recording by another function than this one's (from before baselines named theirs) is none.
        return fingerprints.readBaseline(recorded)
    }

    private fun releaseInput(catalog: CatalogId, release: String): MergeInput {
        val retained = assembler.assemble(catalog, release) ?: throw CatalogReleaseNotFoundException(catalog.key, release)
        return input(catalog.key, retained.content)
    }

    /**
     * The draft the running message targets, as one side: its content and its own dependency
     * declarations (which may name a draft of another catalog), fingerprinted.
     */
    fun draftInput(catalog: CatalogId): MergeInput {
        val content = contentBuilder.build(catalog)
        val declarations = jdbi.withHandle<List<CatalogDependencyDeclaration>, Exception> { dependencyStore.declarations(it, catalog) }
        return input(catalog.key, content, declarations)
    }

    /** One side: every resource and setting of [content], fingerprinted; dependencies from [declarations] when given. */
    fun input(catalogKey: CatalogKey, content: CatalogContent, declarations: List<CatalogDependencyDeclaration>? = null): MergeInput {
        val resourceFingerprints = fingerprints.resourceFingerprints(content)
        val resources = content.resourceDetails.mapNotNull { (contentKey, detail) ->
            val fingerprint = resourceFingerprints[contentKey] ?: return@mapNotNull null
            contentKey to MergeValue.Resource(detail.resource, fingerprint, binariesOf(detail.resource, content))
        }
        val settings = CatalogSetting.of(catalogKey, content, declarations).map { setting ->
            setting.contentKey to MergeValue.Setting(setting, fingerprints.settingFingerprint(setting.canonical))
        }
        return MergeInput(content, (resources + settings).toMap())
    }

    /** The bytes [resource] needs from [content], by archive path: an image's, a font's faces. */
    private fun binariesOf(resource: app.epistola.catalog.protocol.CatalogResource, content: CatalogContent): Map<String, ByteArray> {
        val paths = when (resource) {
            is ImageResource -> listOf(resource.contentPath())
            is FontResource -> resource.variants.map { it.contentPath() }
            else -> emptyList()
        }
        return paths.mapNotNull { path -> content.assetContents[path]?.let { path to it } }.toMap()
    }
}

/** A theme as content names it: its key and the alias of its catalog, which is the catalog's key (ADR 0034). */
data class ThemeByAlias(val themeKey: ThemeKey, val catalogKey: CatalogKey)
