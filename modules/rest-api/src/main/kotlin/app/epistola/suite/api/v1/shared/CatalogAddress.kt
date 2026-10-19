// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.catalog.versions.GetReleasedResource
import app.epistola.suite.catalog.versions.ListReleasedResources
import app.epistola.suite.catalog.versions.ResolveCatalog
import app.epistola.suite.catalog.versions.ResolveCatalogVersion
import app.epistola.suite.catalog.versions.ResolvedCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import java.time.OffsetDateTime

/**
 * The catalog version a `…/catalogs/{registry}/{namespace}/{catalogKey}/versions/{catalogVersion}`
 * address names (contract ADR 0002/0003). Reads on a draft read that draft (see [ref]); reads on a
 * released version read what the release holds; writes need a draft ([ResolvedCatalogVersion.requireDraft]).
 */
internal fun catalogVersionAt(
    tenantKey: String,
    registry: String,
    namespace: String,
    catalogKey: String,
    catalogVersion: String,
): ResolvedCatalogVersion = ResolveCatalogVersion(TenantKey.of(tenantKey), registry, namespace, CatalogKey.of(catalogKey), catalogVersion).query()

/** The draft an address names, for a write: a released version answers 409. */
internal fun draftAt(
    tenantKey: String,
    registry: String,
    namespace: String,
    catalogKey: String,
    catalogVersion: String,
): ResolvedCatalogVersion = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion).requireDraft()

/** The catalog a `…/catalogs/{registry}/{namespace}/{catalogKey}` address, or a request body, names. */
internal fun catalogAt(tenantKey: String, registry: String?, namespace: String, catalogKey: String): Catalog = ResolveCatalog(TenantKey.of(tenantKey), registry, namespace, CatalogKey.of(catalogKey)).query()

internal val ResolvedCatalogVersion.tenantKey: TenantKey get() = catalog.tenantKey

/**
 * The draft this address names, for the commands and queries that work on it
 * ([CatalogVersionScoped][app.epistola.suite.catalog.versions.CatalogVersionScoped]): they then read and
 * write that draft, whichever of the catalog's drafts it is. Null for a released version, whose
 * content is read from the release instead.
 */
internal val ResolvedCatalogVersion.ref: CatalogVersionRef? get() = if (isDraft) CatalogVersionRef(catalog.tenantKey, catalogKey, draftRef) else null

/** How a message names the draft this address names: `next`, or parallel work by its id. */
internal val ResolvedCatalogVersion.draftRef: DraftRef get() = requireNotNull(version.draftRef) { "not a draft" }

internal val ResolvedCatalogVersion.catalogId: CatalogId get() = catalog.catalogId

internal val Catalog.catalogId: CatalogId get() = CatalogId(id, TenantId(tenantKey))

/**
 * The time a released version's content stands for. A release records no per-resource timestamps,
 * so a resource read from it was created and last modified, as far as the release can tell, when it
 * was released.
 */
internal val ResolvedCatalogVersion.releasedAt: OffsetDateTime get() = version.releasedAt ?: catalog.updatedAt

internal val Catalog.isSubscribed: Boolean get() = type == CatalogType.SUBSCRIBED

/** The resource of [type] at [key] the released version holds, or null when it holds none. */
internal inline fun <reified R : CatalogResource> ResolvedCatalogVersion.releasedResource(type: String, key: String): R? = GetReleasedResource(tenantKey, catalogKey, requireNotNull(version.version), type, key).query() as? R

/**
 * Every resource of [type] the released version holds, in key order. A list endpoint pages these in
 * memory: released content is assembled whole anyway, and bounded by the size of the catalog.
 */
internal inline fun <reified R : CatalogResource> ResolvedCatalogVersion.releasedResources(type: String): List<R> = ListReleasedResources(tenantKey, catalogKey, requireNotNull(version.version), type).query().filterIsInstance<R>()
