// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Which release is this? (contract ADR 0003, amendment of 2026-10-22; ADR 0034, phase 2.)
 *
 * A release is identified by its **content hash**, the V6 fingerprint of its content
 * ([CatalogCanonicalizer.contentHash]), which covers no name, number, release block or presentation.
 * Two releases with the same content hash are the same release, whatever number or route they came
 * by. Every release records its content hash beside the fingerprint it was cut or received with
 * (`catalog_versions.content_hash`, NOT NULL): cutting and receiving compute it from the content.
 * 1.x kept no release content, and the 2.0 upgrade deletes its releases before cutting each
 * catalog's first 2.0 release, so no release is without one.
 *
 * Every "is this the same release?" decision goes through [isRelease]: receiving a release the
 * tenant has, a dependency pin, rendering a pinned release, the lineage a release records (its base
 * and merge parents), a merge base. A fingerprint of another algorithm (a pin or a base recorded as
 * V5) is matched against the release's own fingerprint, else checked against its content with the
 * algorithm it names, so a release fingerprinted V5 here and received as V6 elsewhere is still one.
 */
@Component
class ReleaseIdentity(
    private val jdbi: Jdbi,
    private val assembler: ReleaseContentAssembler,
    objectMapper: ObjectMapper,
) {
    private val canonicalizer = CatalogCanonicalizer(objectMapper)

    /** The content hash of [content]. */
    fun contentHashOf(content: CatalogContent): String = canonicalizer.contentHash(content)

    /** The release as recorded: its number, its fingerprint and its content hash. */
    data class Recorded(val version: String, val fingerprint: String, val contentHash: String)

    /** Release [version] of [catalogKey] as recorded, or null when the catalog has no such release. */
    fun recorded(handle: Handle, catalog: CatalogId, version: String): Recorded? = handle
        .createQuery("SELECT version, fingerprint, content_hash FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("v", version)
        .map { rs, _ -> Recorded(rs.getString("version"), rs.getString("fingerprint"), rs.getString("content_hash")) }
        .findOne()
        .orElse(null)

    /** The content hash of release [version] of [catalog], as recorded; null when the catalog has no such release. */
    fun contentHash(handle: Handle, catalog: CatalogId, version: String): String? = recorded(handle, catalog, version)?.contentHash

    /**
     * Whether release [version] of [catalogKey] is the release [fingerprint] names: its own
     * fingerprint, its content hash, or, for a fingerprint of another algorithm (a V5 pin, a legacy
     * bare one), what that algorithm computes over its content. False when the catalog has no such
     * release.
     */
    fun isRelease(handle: Handle, catalog: CatalogId, version: String, fingerprint: String): Boolean {
        val recorded = recorded(handle, catalog, version) ?: return false
        return isRelease(handle, catalog, recorded, fingerprint)
    }

    /** [isRelease] of a release already read as [recorded]. */
    fun isRelease(handle: Handle, catalog: CatalogId, recorded: Recorded, fingerprint: String): Boolean {
        if (recorded.fingerprint == fingerprint || recorded.contentHash == fingerprint) return true
        if (CatalogCanonicalizer.isContentHash(fingerprint)) return false
        // Another algorithm's fingerprint of the same content: check the content with the one it names.
        if (QualifiedCatalogFingerprint.parse(fingerprint) == null && !LEGACY.matches(fingerprint)) return false
        val content = assembler.assemble(catalog, recorded.version)?.content ?: return false
        return canonicalizer.matchesFingerprint(content, fingerprint)
    }

    /**
     * The release of [catalogKey] that [fingerprint] names, whatever its number: the one recorded
     * with that fingerprint or content hash, else, for a content hash, the one whose content hashes
     * to it. Null when the tenant has none.
     */
    fun releaseNamed(handle: Handle, catalog: CatalogId, fingerprint: String): String? {
        val releases = handle
            .createQuery("SELECT version, fingerprint, content_hash FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .map { rs, _ -> Recorded(rs.getString("version"), rs.getString("fingerprint"), rs.getString("content_hash")) }
            .list()
        return releases.firstOrNull { it.fingerprint == fingerprint || it.contentHash == fingerprint }?.version
    }

    /**
     * Release [version] of [catalogKey] as it travels (an export, a registry, the Exchange): its
     * retained content, checked against what identifies it, with its release block naming it by its
     * content hash, as a wire-v8 archive must (`v6:`), whatever fingerprint it was cut with. Null
     * when the catalog has no such release.
     *
     * @throws IllegalStateException when the retained content no longer is the release: it hashes to
     *   another content hash than the one recorded. Handing it on would spread the damage.
     */
    fun travelling(catalog: CatalogId, version: String): RetainedRelease? {
        val retained = assembler.assemble(catalog, version) ?: return null
        val hash = contentHashOf(retained.content)
        jdbi.useHandle<Exception> { handle ->
            val recorded = recorded(handle, catalog, version) ?: return@useHandle
            check(recorded.contentHash == hash) {
                "Release $version of catalog '${catalog.key.value}' rebuilds to $hash but was recorded as " +
                    "${recorded.contentHash}; its retained content has been altered."
            }
        }
        return retained.copy(release = retained.release.copy(fingerprint = hash))
    }

    private companion object {
        /** A fingerprint recorded before fingerprints named their algorithm: bare SHA-256 hex. */
        val LEGACY = Regex("^[0-9a-f]{64}$")
    }
}
