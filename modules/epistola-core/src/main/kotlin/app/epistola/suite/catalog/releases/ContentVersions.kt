// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.DraftReferenceGuard
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import org.jdbi.v3.core.Handle
import java.util.UUID

/**
 * The one place a version address becomes the `version_id` content is read or written at (ADR 0049).
 *
 * Every version of a catalog lives in the same tables, so every statement on content names the
 * version it means by its `version_id`. A message says which version it addresses as it says which
 * tenant ([CatalogReleaseScoped.catalogVersion]: `next`, a draft's id or a release number); its handler
 * resolves that here, once, and binds the result. A message that names none means its catalog's next
 * version (`catalogs.next_version_id`), the default, by name. Nothing is inherited from a caller.
 */
object ContentVersions {
    /**
     * The `version_id` of [catalog] that [message] addresses: the version it names when it names one
     * of this catalog, else the catalog's next version. Null when the catalog has no next version, or
     * none at all: a read then finds nothing, and a write violates its NOT NULL `version_id`.
     *
     * A release is read only by a message that can read one ([ReleaseReadable]), and never written.
     *
     * @throws CatalogNotFoundException when [message] names a version of a catalog the tenant does not have.
     * @throws CatalogReleaseNotFoundException when it names a draft or release the catalog does not have.
     * @throws CatalogReleaseIsReleasedException when a command names, by its id, a draft that was released.
     */
    fun of(handle: Handle, catalog: CatalogId, message: Any?): UUID? {
        val ref = (message as? CatalogReleaseScoped)?.catalogVersion?.takeIf { it.catalog == catalog }
        return resolve(handle, catalog, ref?.draft, releases = message is ReleaseReadable, write = message is Command<*>)
    }

    /**
     * The `version_id` of [catalog] that [ref] names; the catalog's next version when it is null (see
     * [of] for null and the exceptions). [releases] says whether a release may be named, [write]
     * whether the version is about to be written.
     */
    fun resolve(handle: Handle, catalog: CatalogId, ref: DraftRef?, releases: Boolean = true, write: Boolean = false): UUID? = when (ref) {
        null -> next(handle, catalog)

        DraftRef.Next -> next(handle, catalog) ?: missing(handle, catalog, ref)

        is DraftRef.Id -> handle.createQuery(
            "SELECT v.version_id FROM catalog_versions v WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.state = 'draft' AND v.version_key = :id",
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .bind("id", ref.key)
            .mapTo(UUID::class.java)
            .findOne()
            .orElse(null)
            ?: run {
                // A draft that was released keeps its id as the release (ADR 0036): a change made
                // through that id is refused as such, so its author opens the next draft.
                if (write) releasedAs(handle, catalog, ref.key)?.let { throw CatalogReleaseIsReleasedException(catalog.key, it) }
                missing(handle, catalog, ref)
            }

        is DraftRef.Released -> releases.takeIf { it }?.let { release(handle, catalog, ref.version) } ?: missing(handle, catalog, ref)

        // Resolved already, by the work that passes it on; still a version of this catalog.
        is DraftRef.At -> handle.createQuery(
            "SELECT v.version_id FROM catalog_versions v WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.version_id = :v",
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .bind("v", ref.versionId)
            .mapTo(UUID::class.java)
            .findOne()
            .orElse(null)
            ?: missing(handle, catalog, ref)
    }

    /** The `version_id` of [catalog]'s next version: the default, asked for by name. Null when it has none. */
    fun next(handle: Handle, catalog: CatalogId): UUID? = handle
        .createQuery("SELECT c.next_version_id FROM catalogs c WHERE c.tenant_key = :t AND c.catalog_id = ${catalogAt("t", "c")}")
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .mapTo(UUID::class.java)
        .findOne()
        .orElse(null)

    /** The `version_id` of release [version] of [catalog], or null when it has no such release. */
    fun release(handle: Handle, catalog: CatalogId, version: String): UUID? = handle
        .createQuery(
            "SELECT v.version_id FROM catalog_versions v WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.state = 'released' AND v.version = :v",
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("v", version)
        .mapTo(UUID::class.java)
        .findOne()
        .orElse(null)

    /**
     * The `version_id` of [catalog] that command [message] writes ([of]), once it is known to be a
     * draft: the freeze (ADR 0036), only a draft's rows change. Every writer of draft content calls it
     * first (`FrozenVersionWriteTest` holds that), so it also reports the draft to
     * [DraftReferenceGuard], which refuses a write leaving it naming what it cannot resolve. Null when
     * the catalog has no version to write (see [of]).
     *
     * @throws CatalogReleaseIsReleasedException when that version is released (409: open the next draft).
     */
    fun writable(handle: Handle, catalog: CatalogId, message: Any?): UUID? = of(handle, catalog, message)?.also { requireWritable(handle, catalog, it) }

    /** Refuses a write to [versionId] of [catalog] when it is released; see [writable]. */
    fun requireWritable(handle: Handle, catalog: CatalogId, versionId: UUID) {
        val released = handle.createQuery("SELECT v.version FROM catalog_versions v WHERE v.tenant_key = :t AND v.version_id = :v")
            .bind("t", catalog.tenantKey)
            .bind("v", versionId)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
        if (released != null) throw CatalogReleaseIsReleasedException(catalog.key, released)
        DraftReferenceGuard.writing(handle, catalog.tenantKey, versionId)
    }

    private fun releasedAs(handle: Handle, catalog: CatalogId, key: String): String? = handle.createQuery(
        "SELECT v.version FROM catalog_versions v WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.state = 'released' AND v.version_key = :id",
    )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("id", key)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)

    private fun missing(handle: Handle, catalog: CatalogId, ref: DraftRef): Nothing {
        val exists = handle.createQuery("SELECT EXISTS (SELECT 1 FROM catalogs WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")})")
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .mapTo(Boolean::class.java)
            .one()
        if (!exists) throw CatalogNotFoundException(catalog.key)
        throw CatalogReleaseNotFoundException(catalog.key, ref.toString())
    }
}

/**
 * One version of a catalog, as a statement on its content names it (ADR 0049): the catalog, for its
 * address, and the `version_id` its rows carry, bound with
 * [bindVersion][app.epistola.suite.catalog.bindVersion]. [versionId] is null when the catalog has no
 * such version: a read then finds nothing, and a write violates its NOT NULL `version_id`.
 */
data class ContentVersion(val catalog: CatalogId, val versionId: UUID?) {
    val tenantKey: TenantKey get() = catalog.tenantKey

    /**
     * This version as a message addresses it ([DraftRef.At]), for the messages work on it dispatches;
     * null, the catalog's next version, when it has none.
     */
    val ref: CatalogReleaseRef? get() = versionId?.let { CatalogReleaseRef(catalog, DraftRef.At(it)) }

    companion object {
        /** [catalog]'s next version: the default, by name. */
        fun next(handle: Handle, catalog: CatalogId): ContentVersion = ContentVersion(catalog, ContentVersions.next(handle, catalog))
    }
}

/** The version of [catalog] that [message] addresses, else its next version ([ContentVersions.of]). */
fun Handle.versionOf(catalog: CatalogId, message: Any?): ContentVersion = ContentVersion(catalog, ContentVersions.of(this, catalog, message))

/** The version of [catalog] command [message] writes, refused when released ([ContentVersions.writable]). */
fun Handle.writableVersionOf(catalog: CatalogId, message: Any?): ContentVersion = ContentVersion(catalog, ContentVersions.writable(this, catalog, message))

/** This handle, once [at] is known to be a draft ([ContentVersions.requireWritable]): for a writer written as one expression. */
fun Handle.writable(at: ContentVersion): Handle = apply { at.versionId?.let { ContentVersions.requireWritable(this, at.catalog, it) } }

/**
 * The `version_id` [message] addresses, of whichever catalog it names, or null when it names none: for
 * a read across catalogs made for that version, which reads it for its catalog and every other
 * catalog's next version ([inVersionOrNext][app.epistola.suite.catalog.inVersionOrNext]).
 */
fun Handle.addressedVersion(message: Any?): UUID? = (message as? CatalogReleaseScoped)?.catalogVersion?.let { ContentVersions.of(this, it.catalog, message) }

/**
 * The version of [catalog] that [ref] names when it names one of this catalog, else the catalog's next
 * version: for work across catalogs done for one addressed version (a relocation, a merge).
 */
fun Handle.versionIn(catalog: CatalogId, ref: CatalogReleaseRef?): ContentVersion = ContentVersion(catalog, ContentVersions.resolve(this, catalog, ref?.takeIf { it.catalog == catalog }?.draft))
