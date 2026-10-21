// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

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
 * Compared per resource, by the same fingerprints a merge uses, against what the next version held
 * when a received release was put in place in it ([recordBaseline]); an installed copy need not
 * fingerprint as its publisher's digests do. Without that record, against its base release, which is
 * exact for a next version that continues on a release made here, and errs towards "changed" (an
 * upgrade refused, never changes lost) otherwise. A catalog the suite supplies cannot have changes of
 * its own. A next version without a base (nothing released or received under it) holds only changes
 * of its own, so every resource it holds counts as changed; one whose base has no recorded content
 * (a release from before 2.0) counts as unchanged.
 */
@Component
class NextVersionChanges(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val entries: ReleaseEntryStore,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprints: CatalogFingerprintService,
    private val targeting: CatalogVersionTargeting,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) {

    fun hasChanges(tenantKey: TenantKey, catalogKey: CatalogKey): Boolean = changed(tenantKey, catalogKey).isNotEmpty()

    /** The resources (`type/key`) the next version changed since its base: added, changed or removed. */
    fun changed(tenantKey: TenantKey, catalogKey: CatalogKey): Set<String> {
        if (GetCatalogEditability(tenantKey, catalogKey).query().reason == CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA) return emptySet()
        val (next, base) = jdbi.withHandle<Pair<CatalogDraft?, Map<String, String>?>, Exception> { handle ->
            val next = drafts.next(handle, tenantKey, catalogKey)
            val recorded = next?.let { drafts.baseFingerprints(handle, tenantKey, it.versionId) }
            next to when {
                next == null -> null

                recorded != null -> objectMapper.readValue(recorded, BASELINE)

                // Nothing under it: what it holds is all its own.
                next.base == null -> emptyMap()

                else -> entries.entriesOf(handle, tenantKey, catalogKey, next.base).associate { e -> e.contentKey to e.fingerprint }.takeIf { it.isNotEmpty() }
            }
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
        if (jdbi.withHandle<String?, Exception> { drafts.baseFingerprints(it, tenantKey, next.versionId) } != null) return
        val json = objectMapper.writeValueAsString(current(tenantKey, catalogKey, next))
        jdbi.useHandle<Exception> { drafts.recordBaseFingerprints(it, tenantKey, next.versionId, json) }
    }

    private fun current(tenantKey: TenantKey, catalogKey: CatalogKey, next: CatalogDraft): Map<String, String> = targeting.within(next.versionId) { fingerprints.perResourceFingerprints(contentBuilder.build(tenantKey, catalogKey)) }

    private companion object {
        val BASELINE = object : tools.jackson.core.type.TypeReference<Map<String, String>>() {}
    }
}
