// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.queries.CatalogReadOnlyReason
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Whether a catalog's next version has changes of its own since its base (ADR 0032, section 4),
 * which decides what receiving a newer release does: an unchanged next version moves to it (a
 * fast-forward), a changed one stays and is behind it, to be merged.
 *
 * Compared per resource and per catalog setting, by the same fingerprints a merge uses
 * ([CatalogFingerprintService.entryFingerprints]), against what the next version held when a
 * received release was put in place in it ([recordBaseline]); an installed copy need not fingerprint
 * as its publisher's content does. Without that record (or with one another function computed),
 * against its base release's content, recomputed, which is exact for a next version that continues
 * on a release made here, and errs towards "changed" (an upgrade refused, never changes lost)
 * otherwise. A catalog the suite supplies cannot have changes of
 * its own. A next version without a base (nothing released or received under it) holds only changes
 * of its own, so every resource it holds counts as changed; one whose base has no recorded content
 * (a release from before 2.0) counts as unchanged.
 */
@Component
class NextVersionChanges(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val entries: ReleaseEntryStore,
    private val assembler: app.epistola.suite.catalog.revisions.ReleaseContentAssembler,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprints: CatalogFingerprintService,
    private val targeting: CatalogReleaseTargeting,
) {

    fun hasChanges(tenantKey: TenantKey, catalogKey: CatalogKey): Boolean = changed(tenantKey, catalogKey).isNotEmpty()

    /**
     * The entries (`type/key`) the next version changed since its base: added, changed or removed.
     * Its resources and its catalog settings (`catalog/<key>`, the default theme and presets, and
     * `catalogDependency/<catalogKey>` per declared dependency, as a merge compares them too): a
     * setting is a change of its own, which a newer
     * release must not be fast-forwarded over.
     */
    fun changed(tenantKey: TenantKey, catalogKey: CatalogKey): Set<String> {
        if (GetCatalogEditability(tenantKey, catalogKey).query().reason == CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA) return emptySet()
        val (next, recorded, hasEntries) = jdbi.withHandle<Triple<CatalogDraft?, Map<String, String>?, Boolean>, Exception> { handle ->
            val next = drafts.next(handle, tenantKey, catalogKey)
            val recorded = next?.let { fingerprints.readBaseline(drafts.baseFingerprints(handle, tenantKey, it.versionId)) }
            val hasEntries = next?.base?.let { entries.entriesOf(handle, tenantKey, catalogKey, it).isNotEmpty() } ?: false
            Triple(next, recorded, hasEntries)
        }
        val base = when {
            next == null -> null

            // A baseline recorded while every dependency was one entry takes the base's dependency entries.
            recorded != null -> if (next.base == null || app.epistola.suite.catalog.merge.CatalogSetting.LEGACY_DEPENDENCIES !in recorded) {
                recorded
            } else {
                app.epistola.suite.catalog.merge.CatalogSetting.completeBaseline(recorded, assembler.assemble(tenantKey, catalogKey, next.base)?.let { fingerprints.entryFingerprints(catalogKey, it.content) }.orEmpty())
            }

            // Nothing under it: what it holds is all its own, and each setting it set.
            next.base == null -> app.epistola.suite.catalog.merge.CatalogSetting.UNSET.associate { it.contentKey to fingerprints.settingFingerprint(it.canonical) }

            // A release from before 2.0 recorded no content to compare with.
            !hasEntries -> null

            else -> assembler.assemble(tenantKey, catalogKey, next.base)?.let { fingerprints.entryFingerprints(catalogKey, it.content) }
        }
        if (next == null || base == null) return emptySet()
        val ours = current(tenantKey, catalogKey, next)
        return (ours.keys + base.keys).filterTo(sortedSetOf()) { ours[it] != base[it] }
    }

    /**
     * Records what the next version holds now as what a received release put in place, unless that
     * is recorded already. Called once a received release is in place.
     */
    fun recordBaseline(tenantKey: TenantKey, catalogKey: CatalogKey) {
        val next = jdbi.withHandle<CatalogDraft?, Exception> { drafts.next(it, tenantKey, catalogKey) } ?: return
        if (fingerprints.readBaseline(jdbi.withHandle<String?, Exception> { drafts.baseFingerprints(it, tenantKey, next.versionId) }) != null) return
        val json = fingerprints.storeBaseline(current(tenantKey, catalogKey, next))
        jdbi.useHandle<Exception> { drafts.recordBaseFingerprints(it, tenantKey, next.versionId, json) }
    }

    private fun current(tenantKey: TenantKey, catalogKey: CatalogKey, next: CatalogDraft): Map<String, String> = targeting.within(next.versionId) { fingerprints.entryFingerprints(catalogKey, contentBuilder.build(tenantKey, catalogKey)) }
}
