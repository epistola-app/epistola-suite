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
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.catalog.versions.CatalogDefaultTheme
import app.epistola.suite.catalog.versions.CatalogDraft
import app.epistola.suite.catalog.versions.CatalogDrafts
import app.epistola.suite.catalog.versions.CatalogPreset
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * A setting of a catalog version that a release holds beside its resources (contract ADR 0007 and
 * 0003), merged as one entry `catalog/<key>` like a resource. The catalog's name, description and
 * presentation are catalog-wide, not a version's, so they are not merged.
 */
sealed interface CatalogSetting {
    /** Its key under [TYPE]: `catalog/<key>` names it in a merge. */
    val key: String

    /** What people call it. */
    val label: String

    /** Its value in a fixed shape and order, so that equal settings fingerprint equally on every side. */
    val canonical: Any?

    /** The theme a template that names none renders with; null for none. */
    data class DefaultTheme(val theme: CatalogDefaultTheme?) : CatalogSetting {
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

    /** The catalogs it uses, each with the release it pins. */
    data class Dependencies(val declarations: List<CatalogDependencyDeclaration>) : CatalogSetting {
        override val key get() = "dependencies"
        override val label get() = "Catalogs used"
        override val canonical get() = declarations.sortedBy { "${it.registry}/${it.namespace}/${it.catalogKey}" }.map {
            mapOf("registry" to it.registry, "namespace" to it.namespace, "catalogKey" to it.catalogKey, "releaseVersion" to it.releaseVersion, "releaseFingerprint" to it.releaseFingerprint)
        }
    }

    companion object {
        /** The entry type settings are merged under. */
        const val TYPE: String = "catalog"

        /** The settings [content] of [catalogKey] holds, every one of them, set or not. */
        fun of(catalogKey: CatalogKey, content: CatalogContent): List<CatalogSetting> = listOf(
            DefaultTheme(content.catalog.defaultTheme?.let { CatalogDefaultTheme(ThemeKey.of(it.key), it.catalogKey?.let(CatalogKey::of) ?: catalogKey) }),
            Presets(content.catalog.presets.map { CatalogPreset(it.key, it.name, it.description) }),
            Dependencies(content.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of)),
        )
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
    /** The releases the draft contains already ([DraftMerge.contained]). */
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
 * ([CatalogFingerprintService.resourceFingerprints], the complete per-resource form of V5), not taken from `release_entries`: a release
 * received from a source records its publisher's digests there, which need not be what this suite
 * computes for the same content, and comparing the two reports changes nobody made.
 *
 * The merge base is found by the lineage each release records (`catalog_releases.base_version`, as
 * cut here or as a received release named it), never guessed from the release numbers (contract ADR
 * 0005): a release that records none ends its line.
 */
@Component
class MergeInputReader(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val assembler: ReleaseContentAssembler,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprints: CatalogFingerprintService,
) {

    /**
     * The inputs of merging [release] into the draft the running message targets (else the catalog's
     * next version).
     *
     * @throws CatalogVersionNotFoundException when the catalog has no such draft, or no such release.
     */
    fun read(tenantKey: TenantKey, catalogKey: CatalogKey, release: String, draftName: String): MergeInputs {
        val draft = jdbi.withHandle<_, Exception> { drafts.draft(it, tenantKey, catalogKey) }
            ?: throw CatalogVersionNotFoundException(catalogKey, draftName)
        val lines = jdbi.withHandle<Map<String, ReleaseLine>, Exception> { releaseLines(it, tenantKey, catalogKey) }
        if (release !in lines) throw CatalogVersionNotFoundException(catalogKey, release)
        val madeFrom = { r: String -> lines[r]?.let { line -> line.baseVersion?.let { DraftMerge.MadeFrom(it, line.baseFingerprint) } } }
        val baseOf = { r: String -> madeFrom(r)?.version }
        val contained = DraftMerge.contained(draft.base, draft.merged, baseOf)
        val found = DraftMerge.mergeBaseOf(release, contained, madeFrom)
        val installed = found?.takeIf { lines[it.version]?.let { line -> it.fingerprint == null || sameRelease(tenantKey, catalogKey, it.version, line.fingerprint, it.fingerprint) } == true }
        // Found by lineage and not here (or another release under its number): named, to get first. One
        // that is not here and whose fingerprint nobody named cannot be asked for, so no base is known.
        val missing = found?.takeIf { installed == null }?.fingerprint?.let { MergeBaseRef(found.version, it) }
        val mergeBase = installed?.version
        val base = mergeBase?.let { releaseInput(tenantKey, catalogKey, it) } ?: MergeInput.NONE
        return MergeInputs(
            draft = draft,
            release = release,
            mergeBase = mergeBase,
            contained = contained,
            base = base,
            ours = input(catalogKey, contentBuilder.build(tenantKey, catalogKey)),
            theirs = releaseInput(tenantKey, catalogKey, release),
            heldBase = mergeBase?.let { heldBase(tenantKey, catalogKey, draft, it, base, baseOf) }.orEmpty(),
            missingBase = missing,
        )
    }

    /** Whether the release installed as [version] with [installed] is the one [expected] names. */
    private fun sameRelease(tenantKey: TenantKey, catalogKey: CatalogKey, version: String, installed: String, expected: String): Boolean {
        if (installed == expected) return true
        // Another algorithm's fingerprint of the same content (a release from before V5, say): check the content.
        val content = assembler.assemble(tenantKey, catalogKey, version)?.content ?: return false
        return fingerprints.matchesFingerprint(content, expected)
    }

    /**
     * [MergeInputs.heldBase] of [mergeBase] in [draft]: what the draft recorded holding of it (its
     * base, or a release it merged), else of a release it holds that descends from [mergeBase], for
     * every entry the two releases have alike; else the base as released.
     */
    private fun heldBase(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        draft: CatalogDraft,
        mergeBase: String,
        base: MergeInput,
        baseOf: (String) -> String?,
    ): Map<String, String> {
        val anchors = listOfNotNull(draft.base) + draft.merged
        for (anchor in anchors) {
            if (!descendsFrom(anchor, mergeBase, baseOf)) continue
            val held = recordedHeld(tenantKey, draft, anchor) ?: continue
            if (anchor == mergeBase) return withSettings(held, base)
            // What the draft holds of the anchor is what it holds of the merge base wherever the two
            // releases are alike; elsewhere the anchor changed it, and the base as released is compared.
            val anchorReleased = releaseInput(tenantKey, catalogKey, anchor).fingerprints
            val translated = base.fingerprints.mapValues { (key, released) -> if (anchorReleased[key] == released) held[key] ?: released else released }
            return translated
        }
        return base.fingerprints
    }

    /** Whether [release] is [ancestor] or descends from it by recorded lineage. */
    private fun descendsFrom(release: String, ancestor: String, baseOf: (String) -> String?): Boolean {
        val seen = mutableSetOf<String>()
        var current: String? = release
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = baseOf(current)
        }
        return false
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

    /** A recording from before settings were merged holds resources only; its settings are the base's. */
    private fun withSettings(held: Map<String, String>, base: MergeInput): Map<String, String> = held + base.fingerprints.filterKeys { it.startsWith("${CatalogSetting.TYPE}/") && it !in held }

    private fun releaseInput(tenantKey: TenantKey, catalogKey: CatalogKey, release: String): MergeInput {
        val retained = assembler.assemble(tenantKey, catalogKey, release) ?: throw CatalogVersionNotFoundException(catalogKey, release)
        return input(catalogKey, retained.content)
    }

    /** One side: every resource and setting of [content], fingerprinted. */
    fun input(catalogKey: CatalogKey, content: CatalogContent): MergeInput {
        val resourceFingerprints = fingerprints.resourceFingerprints(content)
        val resources = content.resourceDetails.mapNotNull { (contentKey, detail) ->
            val fingerprint = resourceFingerprints[contentKey] ?: return@mapNotNull null
            contentKey to MergeValue.Resource(detail.resource, fingerprint, binariesOf(detail.resource, content))
        }
        val settings = CatalogSetting.of(catalogKey, content).map { setting ->
            "${CatalogSetting.TYPE}/${setting.key}" to MergeValue.Setting(setting, fingerprints.settingFingerprint(setting.canonical))
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

    /** A release of the catalog: its fingerprint, and the release it records it was made from. */
    private data class ReleaseLine(val fingerprint: String, val baseVersion: String?, val baseFingerprint: String?)

    /** Every release of the catalog with the lineage it records. */
    private fun releaseLines(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, ReleaseLine> = handle
        .createQuery("SELECT version, fingerprint, base_version, base_fingerprint FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map { rs, _ -> rs.getString("version") to ReleaseLine(rs.getString("fingerprint"), rs.getString("base_version"), rs.getString("base_fingerprint")) }
        .list()
        .toMap()
}
