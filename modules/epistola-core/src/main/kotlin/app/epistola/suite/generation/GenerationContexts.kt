// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation

import app.epistola.generation.pdf.GenerationContext
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.generation.release.ReleaseRenderInputs

/**
 * Where a document generated for this request came from, as the request recorded it when it was
 * accepted: written to the PDF's XMP (`docs/generation.md`, "Generation context"). Never the data
 * or the correlation id.
 */
fun DocumentGenerationRequest.generationContext(): GenerationContext = GenerationContext(
    tenant = tenantKey.value,
    registry = registry,
    namespace = namespace,
    catalog = catalogKey.value,
    template = templateKey.value,
    variant = variantKey.value,
    release = releaseVersion,
    draft = draftId,
    draftLabel = draftLabel,
    catalogVersion = catalogVersionId,
    environment = environmentKey?.value,
    request = id.value.toString(),
    batch = batchId?.value?.toString(),
)

/** Where a preview of this release came from: no request, and marked as a preview. */
fun ReleaseRenderInputs.previewContext(template: String, variant: VariantKey, environment: EnvironmentKey?): GenerationContext = GenerationContext(
    tenant = release.catalog.tenantKey.value,
    namespace = release.catalog.namespace,
    catalog = release.catalogKey.value,
    template = template,
    variant = variant.value,
    release = release.version,
    environment = environment?.value,
    preview = true,
)
