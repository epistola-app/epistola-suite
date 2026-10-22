// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Which release is this? (contract ADR 0003, amendment of 2026-10-22; ADR 0034, phase 2.)
 *
 * A release is identified by its **content hash**, the V6 fingerprint of its content
 * ([CatalogCanonicalizer.contentHash]), which covers no name, number, release block or presentation.
 * Two releases with the same content hash are the same release, whatever number or route they came
 * by. Each release records its content hash beside the fingerprint it was cut or received with
 * (`catalog_releases.content_hash`): its fingerprint when that is V6, else computed from what it
 * retained, by [ReleaseContentHashBackfill] or on first use here.
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
    private val logger = LoggerFactory.getLogger(javaClass)
    private val canonicalizer = CatalogCanonicalizer(objectMapper)

    /** The content hash of [content]. */
    fun contentHashOf(content: CatalogContent): String = canonicalizer.contentHash(content)

    /** The release as recorded: its number, its fingerprint and its content hash (null until computed). */
    data class Recorded(val version: String, val fingerprint: String, val contentHash: String?)

    /** Release [version] of [catalogKey] as recorded, or null when the catalog has no such release. */
    fun recorded(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): Recorded? = handle
        .createQuery("SELECT version, fingerprint, content_hash FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("v", version)
        .map { rs, _ -> Recorded(rs.getString("version"), rs.getString("fingerprint"), rs.getString("content_hash")) }
        .findOne()
        .orElse(null)

    /**
     * The content hash of release [version] of [catalogKey]: recorded, else computed from what it
     * retained and recorded now. Null when the catalog has no such release, or when its retained
     * content cannot be read back (logged).
     */
    fun contentHash(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): String? {
        val recorded = recorded(handle, tenantKey, catalogKey, version) ?: return null
        return contentHash(handle, tenantKey, catalogKey, recorded)
    }

    /** [contentHash] of a release already read as [recorded]. */
    fun contentHash(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, recorded: Recorded): String? {
        recorded.contentHash?.let { return it }
        if (CatalogCanonicalizer.isContentHash(recorded.fingerprint)) return record(handle, tenantKey, catalogKey, recorded.version, recorded.fingerprint)
        val content = try {
            assembler.assemble(tenantKey, catalogKey, recorded.version)?.content
        } catch (e: RuntimeException) {
            logger.warn("Release {}@{} of tenant {} has no readable content to hash: {}", catalogKey.value, recorded.version, tenantKey.value, e.message)
            null
        } ?: return null
        return record(handle, tenantKey, catalogKey, recorded.version, contentHashOf(content))
    }

    /**
     * Whether release [version] of [catalogKey] is the release [fingerprint] names: its own
     * fingerprint, its content hash, or, for a fingerprint of another algorithm (a V5 pin, a legacy
     * bare one), what that algorithm computes over its content. False when the catalog has no such
     * release.
     */
    fun isRelease(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, fingerprint: String): Boolean {
        val recorded = recorded(handle, tenantKey, catalogKey, version) ?: return false
        return isRelease(handle, tenantKey, catalogKey, recorded, fingerprint)
    }

    /** [isRelease] of a release already read as [recorded]. */
    fun isRelease(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, recorded: Recorded, fingerprint: String): Boolean {
        if (recorded.fingerprint == fingerprint || recorded.contentHash == fingerprint) return true
        if (CatalogCanonicalizer.isContentHash(fingerprint)) return contentHash(handle, tenantKey, catalogKey, recorded) == fingerprint
        // Another algorithm's fingerprint of the same content: check the content with the one it names.
        if (QualifiedCatalogFingerprint.parse(fingerprint) == null && !LEGACY.matches(fingerprint)) return false
        val content = assembler.assemble(tenantKey, catalogKey, recorded.version)?.content ?: return false
        return canonicalizer.matchesFingerprint(content, fingerprint)
    }

    /**
     * The release of [catalogKey] that [fingerprint] names, whatever its number: the one recorded
     * with that fingerprint or content hash, else, for a content hash, the one whose content hashes
     * to it. Null when the tenant has none.
     */
    fun releaseNamed(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, fingerprint: String): String? {
        val releases = handle
            .createQuery("SELECT version, fingerprint, content_hash FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ -> Recorded(rs.getString("version"), rs.getString("fingerprint"), rs.getString("content_hash")) }
            .list()
        releases.firstOrNull { it.fingerprint == fingerprint || it.contentHash == fingerprint }?.let { return it.version }
        if (!CatalogCanonicalizer.isContentHash(fingerprint)) return null
        return releases.filter { it.contentHash == null }.firstOrNull { contentHash(handle, tenantKey, catalogKey, it) == fingerprint }?.version
    }

    /**
     * Release [version] of [catalogKey] as it travels (an export, a registry, the Exchange): its
     * retained content, checked against what identifies it, with its release block naming it by its
     * content hash, as a wire-v8 archive must (`v6:`), whatever fingerprint it was cut with. Null
     * when the catalog has no such release.
     *
     * @throws IllegalStateException when the retained content no longer is the release: it hashes to
     *   another content hash than the one recorded, or, for a release without one yet, does not
     *   reproduce the fingerprint it was cut with. Handing it on would spread the damage.
     */
    fun travelling(tenantKey: TenantKey, catalogKey: CatalogKey, version: String): RetainedRelease? {
        val retained = assembler.assemble(tenantKey, catalogKey, version) ?: return null
        val hash = contentHashOf(retained.content)
        jdbi.useHandle<Exception> { handle ->
            val recorded = recorded(handle, tenantKey, catalogKey, version) ?: return@useHandle
            val holds = recorded.contentHash?.let { it == hash }
                ?: (recorded.fingerprint == hash || canonicalizer.matchesFingerprint(retained.content, recorded.fingerprint))
            check(holds) {
                "Release $version of catalog '${catalogKey.value}' rebuilds to $hash but was recorded as " +
                    "${recorded.contentHash ?: recorded.fingerprint}; its retained content has been altered."
            }
            if (recorded.contentHash == null) record(handle, tenantKey, catalogKey, version, hash)
        }
        return retained.copy(release = retained.release.copy(fingerprint = hash))
    }

    private fun record(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, contentHash: String): String {
        handle.createUpdate(
            "UPDATE catalog_releases SET content_hash = :h WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v AND content_hash IS NULL",
        )
            .bind("h", contentHash)
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("v", version)
            .execute()
        return contentHash
    }

    private companion object {
        /** A fingerprint recorded before fingerprints named their algorithm: bare SHA-256 hex. */
        val LEGACY = Regex("^[0-9a-f]{64}$")
    }
}
