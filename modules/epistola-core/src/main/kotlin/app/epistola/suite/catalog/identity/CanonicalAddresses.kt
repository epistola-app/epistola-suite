// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.identity

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query

/**
 * The address this template occupies now, for an id built from one it may have moved away from.
 *
 * Every REST, MCP and UI entry point that takes a template, stencil or attribute address from
 * outside goes through one of these before dispatching, so a relocation does not break what was
 * bookmarked or configured against the old address. Unchanged when already canonical or when
 * nothing is there: a miss stays the caller's own not-found. Runs in the caller's bound mediator
 * context.
 */
fun TemplateId.canonical(): TemplateId = canonicalAddress(tenantKey, CatalogResourceType.TEMPLATE, catalogKey.value, key.value)
    ?.let { TemplateId(TemplateKey.of(it.key), CatalogId(CatalogKey.of(it.catalogKey), tenantId)) }
    ?: this

/** See [TemplateId.canonical]. */
fun StencilId.canonical(): StencilId = canonicalAddress(tenantKey, CatalogResourceType.STENCIL, catalogKey.value, key.value)
    ?.let { StencilId(StencilKey.of(it.key), CatalogId(CatalogKey.of(it.catalogKey), tenantId)) }
    ?: this

/** See [TemplateId.canonical]. */
fun AttributeId.canonical(): AttributeId = canonicalAddress(tenantKey, CatalogResourceType.ATTRIBUTE, catalogKey.value, key.value)
    ?.let { AttributeId(AttributeKey.of(it.key), CatalogId(CatalogKey.of(it.catalogKey), tenantId)) }
    ?: this

private fun canonicalAddress(tenantKey: TenantKey, type: CatalogResourceType, catalogKey: String, key: String): ResourceAddress? = ResolveCanonicalResourceAddress(tenantKey, ResourceAddress(type, catalogKey, key)).query()
