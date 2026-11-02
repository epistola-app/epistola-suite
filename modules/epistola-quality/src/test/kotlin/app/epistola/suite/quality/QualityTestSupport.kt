// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.GetQualityInputKey

/**
 * A submission as `RunQualityChecks` makes one: under the variant's input key in [catalogVersion]
 * (its catalog's next version when null), read now (ADR 0044). For tests that submit findings by
 * hand rather than through a source.
 */
fun SubmitQualityFindings(
    sourceId: QualitySourceId,
    subject: QualitySubject,
    findings: List<SubmittedFinding>,
    catalogVersion: CatalogReleaseRef? = null,
): SubmitQualityFindings {
    val variantKey = requireNotNull(subject.variantKey) { "a check is of a variant; ${subject.urn} is not one" }
    val variantId = VariantId(VariantKey.of(variantKey), TemplateId(subject.templateKey, subject.catalogId))
    val inputKey = requireNotNull(GetQualityInputKey(variantId, catalogVersion).query().inputKey) { "${subject.urn} is in no such version" }
    return SubmitQualityFindings(sourceId, subject, inputKey, findings, catalogVersion)
}
