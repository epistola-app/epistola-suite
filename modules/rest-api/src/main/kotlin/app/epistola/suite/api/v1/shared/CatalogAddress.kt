// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.GetReleasedResource
import app.epistola.suite.catalog.releases.ListReleasedResources
import app.epistola.suite.catalog.releases.ResolveCatalog
import app.epistola.suite.catalog.releases.ResolveCatalogRelease
import app.epistola.suite.catalog.releases.ResolvedCatalogRelease
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import java.time.OffsetDateTime

/**
 * The catalog version a `…/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}`
 * address names (contract ADR 0002/0003). Reads on a draft read that draft (see [ref]); reads on a
 * released version read what the release holds; writes need a draft ([ResolvedCatalogRelease.requireDraft]).
 */
internal fun catalogVersionAt(
    tenantKey: String,
    registry: String,
    namespace: String,
    catalogKey: String,
    catalogVersion: String,
): ResolvedCatalogRelease = ResolveCatalogRelease(addressOf(tenantKey, namespace, catalogKey), registry, catalogVersion).query()

/** The draft an address names, for a write: a released version answers 409. */
internal fun draftAt(
    tenantKey: String,
    registry: String,
    namespace: String,
    catalogKey: String,
    catalogVersion: String,
): ResolvedCatalogRelease = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion).requireDraft()

/** The catalog a `…/catalogs/{registry}/{namespace}/{catalogKey}` address, or a request body, names. */
internal fun catalogAt(tenantKey: String, registry: String?, namespace: String, catalogKey: String): Catalog = ResolveCatalog(addressOf(tenantKey, namespace, catalogKey), registry).query()

/** The catalog a path names by its namespace and key (ADR 0034, section 9). */
internal fun addressOf(tenantKey: String, namespace: String, catalogKey: String): CatalogId = CatalogId(namespace, CatalogKey.of(catalogKey), TenantId(TenantKey.of(tenantKey)))

internal val ResolvedCatalogRelease.tenantKey: TenantKey get() = catalog.tenantKey

/**
 * The draft this address names, for the commands and queries that work on it
 * ([CatalogReleaseScoped][app.epistola.suite.catalog.releases.CatalogReleaseScoped]): they then read and
 * write that draft, whichever of the catalog's drafts it is. Null for a released version, whose
 * content is read from the release instead.
 */
internal val ResolvedCatalogRelease.ref: CatalogReleaseRef? get() = if (isDraft) CatalogReleaseRef(catalog.catalogId, draftRef) else null

/**
 * The version a read of a resource type that reads releases through the draft queries means (ADR
 * 0033): the draft, or the release by its number.
 */
internal val ResolvedCatalogRelease.readRef: CatalogReleaseRef get() = ref ?: CatalogReleaseRef(catalog.catalogId, app.epistola.suite.catalog.releases.DraftRef.Released(requireNotNull(version.version)))

/** How a message names the draft this address names: `next`, or parallel work by its id. */
internal val ResolvedCatalogRelease.draftRef: DraftRef get() = requireNotNull(version.draftRef) { "not a draft" }

internal val ResolvedCatalogRelease.catalogId: CatalogId get() = catalog.catalogId

/**
 * The time a released version's content stands for. A release records no per-resource timestamps,
 * so a resource read from it was created and last modified, as far as the release can tell, when it
 * was released.
 */
internal val ResolvedCatalogRelease.releasedAt: OffsetDateTime get() = version.releasedAt ?: catalog.updatedAt

/** Whether this catalog cannot be changed here (ADR 0032): the `readOnly` of a resource DTO in it. */
internal fun Catalog.readOnlyHere(): Boolean = !GetCatalogEditability(catalogId).query().editable

/** The resource of [type] at [key] the released version holds, or null when it holds none. */
internal inline fun <reified R : CatalogResource> ResolvedCatalogRelease.releasedResource(type: String, key: String): R? = GetReleasedResource(catalog.catalogId, requireNotNull(version.version), type, key).query() as? R

/**
 * Every resource of [type] the released version holds, in key order. A list endpoint pages these in
 * memory: released content is assembled whole anyway, and bounded by the size of the catalog.
 */
internal inline fun <reified R : CatalogResource> ResolvedCatalogRelease.releasedResources(type: String): List<R> = ListReleasedResources(catalog.catalogId, requireNotNull(version.version), type).query().filterIsInstance<R>()
