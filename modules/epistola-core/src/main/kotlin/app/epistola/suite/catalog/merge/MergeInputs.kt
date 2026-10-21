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
import app.epistola.suite.fonts.model.sha256Hex
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

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
 * What merging [release] into [draft] starts from: the merge base ([mergeBase], the closest release
 * both descend from; null when none), the draft (ours) and the release (theirs).
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
     * that is used. Otherwise the base as released.
     */
    val heldBase: Map<String, String> = base.fingerprints,
)

/**
 * Reads [MergeInputs]. The draft's content is built from its rows; each release's from the content it
 * retained ([ReleaseContentAssembler]). All three are fingerprinted by the same function
 * ([CatalogFingerprintService.perResourceFingerprints]), not taken from `release_entries`: a release
 * received from a source records its publisher's digests there, which need not be what this suite
 * computes for the same content, and comparing the two reports changes nobody made.
 */
@Component
class MergeInputReader(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val assembler: ReleaseContentAssembler,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprints: CatalogFingerprintService,
    private val objectMapper: ObjectMapper,
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
        val bases = jdbi.withHandle<Map<String, String?>, Exception> { releaseBases(it, tenantKey, catalogKey) }
        if (release !in bases) throw CatalogVersionNotFoundException(catalogKey, release)
        // A release's base as it recorded it, else the highest release below it (releases recorded
        // before bases were, and installed ones, carry none).
        val baseOf = { r: String -> bases[r] ?: CatalogDrafts.highestBelow(r, bases.keys) }
        val contained = DraftMerge.contained(draft.base, draft.merged, baseOf)
        val mergeBase = DraftMerge.mergeBase(release, contained, baseOf)
        val base = mergeBase?.let { releaseInput(tenantKey, catalogKey, it) } ?: MergeInput.NONE
        return MergeInputs(
            draft = draft,
            release = release,
            mergeBase = mergeBase,
            contained = contained,
            base = base,
            ours = input(catalogKey, contentBuilder.build(tenantKey, catalogKey)),
            theirs = releaseInput(tenantKey, catalogKey, release),
            heldBase = mergeBase?.let { heldBase(tenantKey, draft, it, base) }.orEmpty(),
        )
    }

    /** [MergeInputs.heldBase] of [mergeBase] in [draft]. */
    private fun heldBase(tenantKey: TenantKey, draft: CatalogDraft, mergeBase: String, base: MergeInput): Map<String, String> {
        val recorded = jdbi.withHandle<String?, Exception> { handle ->
            when (mergeBase) {
                draft.base -> drafts.baseFingerprints(handle, tenantKey, draft.versionId)
                in draft.merged -> drafts.mergeHeldFingerprints(handle, tenantKey, draft.versionId, mergeBase)
                else -> null
            }
        } ?: return base.fingerprints
        val held = objectMapper.readValue(recorded, HELD)
        // A recording from before settings were merged holds resources only; its settings are the base's.
        val settings = base.fingerprints.filterKeys { it.startsWith("${CatalogSetting.TYPE}/") && it !in held }
        return held + settings
    }

    private fun releaseInput(tenantKey: TenantKey, catalogKey: CatalogKey, release: String): MergeInput {
        val retained = assembler.assemble(tenantKey, catalogKey, release) ?: throw CatalogVersionNotFoundException(catalogKey, release)
        return input(catalogKey, retained.content)
    }

    /** One side: every resource and setting of [content], fingerprinted. */
    fun input(catalogKey: CatalogKey, content: CatalogContent): MergeInput {
        val resourceFingerprints = fingerprints.perResourceFingerprints(content)
        val resources = content.resourceDetails.mapNotNull { (contentKey, detail) ->
            val fingerprint = resourceFingerprints[contentKey] ?: return@mapNotNull null
            contentKey to MergeValue.Resource(detail.resource, fingerprint, binariesOf(detail.resource, content))
        }
        val settings = CatalogSetting.of(catalogKey, content).map { setting ->
            "${CatalogSetting.TYPE}/${setting.key}" to MergeValue.Setting(setting, sha256Hex(objectMapper.writeValueAsBytes(setting.canonical)))
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

    private companion object {
        val HELD = object : tools.jackson.core.type.TypeReference<Map<String, String>>() {}
    }

    /** Every release of the catalog with the base it recorded (null when none). */
    private fun releaseBases(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): Map<String, String?> = handle
        .createQuery("SELECT version, base_version FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map { rs, _ -> rs.getString("version") to rs.getString("base_version") }
        .list()
        .toMap()
}
