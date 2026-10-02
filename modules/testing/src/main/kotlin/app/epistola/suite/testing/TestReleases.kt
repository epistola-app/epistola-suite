// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.templates.queries.versions.GetLatestPublishedVersion

/**
 * Publishes [variantId]'s draft and cuts the next release of its catalog, so generation can render it.
 *
 * From 2.0 generation renders a catalog release and nothing else, so a test that generates a document
 * needs one. The template gets the minimum publishable data contract first, because publishing
 * requires an example.
 *
 * @return the published version, for assertions that still look at it.
 */
fun Mediator.publishAndRelease(variantId: VariantId): TemplateVersion {
    // Only when the template has no example yet: a test that set its own contract keeps it.
    if (query(GetLatestContractVersion(variantId.templateId))?.dataExamples.isNullOrEmpty()) {
        ensureRequiredDataExample(variantId.templateId)
    }
    val draft = requireNotNull(query(GetDraft(variantId))) { "Variant ${variantId.key.value} has no draft to publish" }
    val published = requireNotNull(send(PublishVersion(VersionId(draft.id, variantId)))) { "Publishing ${variantId.key.value} returned nothing" }
    ensureDefaultVariantPublished(variantId, draft.templateModel)
    releaseNext(variantId.templateId.catalogId)
    return published
}

/**
 * Cuts the next patch release of [catalogId] (`1.0.0` for its first), capturing the working copy.
 *
 * @return the version cut.
 */
fun Mediator.releaseNext(catalogId: CatalogId): String {
    val latest = query(GetLatestCatalogRelease(catalogId.tenantKey, catalogId.key)).latestVersion
    val next = latest?.let { SemVer.parse(it).bumpPatch().toString() } ?: "1.0.0"
    send(ReleaseCatalogVersion(tenantKey = catalogId.tenantKey, catalogKey = catalogId.key, version = next))
    return next
}

/**
 * A release carries a template only when its default variant has a published model: the wire form
 * hoists that model onto the template. A test that publishes some other variant would otherwise cut a
 * release without the template at all, so the default variant gets the same model when it has none.
 */
private fun Mediator.ensureDefaultVariantPublished(published: VariantId, model: TemplateDocument) {
    val default = query(GetVariantSummaries(published.templateId)).firstOrNull { it.isDefault } ?: return
    if (default.id == published.key) return
    val defaultId = VariantId(default.id, published.templateId)
    if (query(GetLatestPublishedVersion(defaultId)) != null) return
    send(UpdateDraft(variantId = defaultId, templateModel = model))
    val draft = requireNotNull(query(GetDraft(defaultId)))
    send(PublishVersion(VersionId(draft.id, defaultId)))
}
