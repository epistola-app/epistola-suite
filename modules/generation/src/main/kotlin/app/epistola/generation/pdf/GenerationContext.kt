// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

/**
 * Where a document came from: the release, template and request that produced it. Written to the
 * PDF's XMP metadata under [GenerationContextXmp.NAMESPACE], so whoever holds the PDF can tell what
 * rendered it without asking Epistola.
 *
 * Identifiers only. Never the submitted data, and never the caller's correlation id, which often
 * holds a case number: a PDF travels further than the request did.
 *
 * The render time, the locale and the engine are not set here; the renderer writes the values it
 * rendered with.
 *
 * @property tenant the tenant's key
 * @property registry the catalog's registry; null for a request accepted before it was recorded
 * @property namespace the catalog's namespace; null likewise
 * @property catalog the catalog's key
 * @property template the template's key
 * @property variant the variant's key
 * @property release the catalog release rendered; null for a draft
 * @property draft the id of the draft rendered, live, through an environment following it
 * @property draftLabel the draft's name when it was rendered
 * @property catalogVersion the id of the catalog version rendered, a release's or a draft's
 * @property environment the environment the request named
 * @property request the generation request's id; null for a preview
 * @property batch the batch the request belongs to
 * @property preview whether this is a preview rather than a generated document
 */
data class GenerationContext(
    val tenant: String,
    val registry: String? = null,
    val namespace: String? = null,
    val catalog: String,
    val template: String,
    val variant: String,
    val release: String? = null,
    val draft: String? = null,
    val draftLabel: String? = null,
    val catalogVersion: String? = null,
    val environment: String? = null,
    val request: String? = null,
    val batch: String? = null,
    val preview: Boolean = false,
)
