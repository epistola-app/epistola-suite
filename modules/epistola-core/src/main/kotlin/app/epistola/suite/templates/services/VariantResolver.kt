// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.services

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.queries.variants.ListVariants
import org.springframework.stereotype.Component

/**
 * Criteria for selecting a variant based on attribute matching.
 *
 * @property requiredAttributes Attributes that MUST match (variant is excluded if any required attribute doesn't match)
 * @property optionalAttributes Attributes that are preferred but not mandatory (used for scoring)
 */
data class VariantSelectionCriteria(
    val requiredAttributes: Map<String, String> = emptyMap(),
    val optionalAttributes: Map<String, String> = emptyMap(),
)

/**
 * Thrown when no variant matches the required attributes and no default variant exists.
 */
class NoMatchingVariantException(
    val templateId: TemplateKey,
    val criteria: VariantSelectionCriteria,
) : RuntimeException(
    "No variant found for template '$templateId' matching required attributes: ${criteria.requiredAttributes}",
)

/**
 * Thrown when multiple variants have the same score and cannot be disambiguated.
 */
class AmbiguousVariantResolutionException(
    val templateId: TemplateKey,
    val tiedVariantIds: List<VariantKey>,
    val score: Int,
) : RuntimeException(
    "Ambiguous variant resolution for template '$templateId': variants ${tiedVariantIds.joinToString(", ")} " +
        "all have score $score. Add more attributes to disambiguate.",
)

/** One variant as selection sees it: its key, its attributes, and whether it is the default. */
data class VariantCandidate(
    val id: VariantKey,
    val attributes: Map<String, String>,
    val isDefault: Boolean,
)

/**
 * Resolves a variant for a template based on attribute-matching criteria.
 *
 * Algorithm:
 * 1. Fetch all variants for the template
 * 2. Filter: keep only variants that match ALL required attributes
 * 3. Score remaining variants: (requiredMatches * 100) + (optionalMatches * 10)
 * 4. Select highest score. If tied: [AmbiguousVariantResolutionException]
 * 5. If no variant passes required filter: fall back to default variant (is_default = true), else error
 */
@Component
class VariantResolver {

    /**
     * Resolves the best matching variant for the given template and criteria.
     *
     * [template] carries its catalog: template keys are unique only within a catalog, so selection
     * must never fall back to `default` (#1021).
     *
     * @return the ID of the resolved variant
     * @throws NoMatchingVariantException if no variant matches and no default exists
     * @throws AmbiguousVariantResolutionException if multiple variants tie on score
     */
    fun resolve(
        template: TemplateId,
        criteria: VariantSelectionCriteria,
    ): VariantKey {
        val variants = ListVariants(template).query()
        return select(
            template.key,
            variants.map { VariantCandidate(it.id, it.attributes, it.isDefault) },
            criteria,
        )
    }

    /**
     * The selection rule on its own, over any set of variants: the working copy's, or the ones a
     * catalog release holds. Both must pick the same variant for the same request.
     *
     * @throws NoMatchingVariantException if no variant matches and no default exists
     * @throws AmbiguousVariantResolutionException if multiple variants tie on score
     */
    fun select(
        templateId: TemplateKey,
        variants: List<VariantCandidate>,
        criteria: VariantSelectionCriteria,
    ): VariantKey {
        // Filter variants that match ALL required attributes
        val candidates = variants.filter { variant ->
            matchesAllRequired(variant, criteria.requiredAttributes)
        }

        if (candidates.isEmpty()) {
            // Fall back to default variant (is_default = true)
            val defaultVariant = variants.find { it.isDefault }
                ?: throw NoMatchingVariantException(templateId, criteria)
            return defaultVariant.id
        }

        // Score candidates based on matched attributes only (not total attribute count)
        // requiredMatches are weighted higher since they confirm explicit intent
        val scored = candidates.map { variant ->
            val requiredMatches = countRequiredMatches(variant, criteria.requiredAttributes)
            val optionalMatches = countOptionalMatches(variant, criteria.optionalAttributes)
            val score = (requiredMatches * 100) + (optionalMatches * 10)
            ScoredVariant(variant, score)
        }

        val maxScore = scored.maxOf { it.score }
        val topCandidates = scored.filter { it.score == maxScore }

        if (topCandidates.size > 1) {
            throw AmbiguousVariantResolutionException(
                templateId = templateId,
                tiedVariantIds = topCandidates.map { it.variant.id },
                score = maxScore,
            )
        }

        return topCandidates.single().variant.id
    }

    private fun matchesAllRequired(variant: VariantCandidate, required: Map<String, String>): Boolean = required.all { (key, value) -> variant.attributes[key] == value }

    private fun countRequiredMatches(variant: VariantCandidate, required: Map<String, String>): Int = required.count { (key, value) -> variant.attributes[key] == value }

    private fun countOptionalMatches(variant: VariantCandidate, optional: Map<String, String>): Int = optional.count { (key, value) -> variant.attributes[key] == value }

    private data class ScoredVariant(val variant: VariantCandidate, val score: Int)
}
