package app.epistola.suite.templates.services

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
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
    val templateId: TemplateId,
    val criteria: VariantSelectionCriteria,
) : RuntimeException(
    "No variant found for template '$templateId' matching required attributes: ${criteria.requiredAttributes}",
)

/**
 * Thrown when multiple variants have the same score and cannot be disambiguated.
 */
class AmbiguousVariantResolutionException(
    val templateId: TemplateId,
    val tiedVariantIds: List<VariantId>,
    val score: Int,
) : RuntimeException(
    "Ambiguous variant resolution for template '$templateId': variants ${tiedVariantIds.joinToString(", ")} " +
        "all have score $score. Add more attributes to disambiguate.",
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
     * @return the ID of the resolved variant
     * @throws NoMatchingVariantException if no variant matches and no default exists
     * @throws AmbiguousVariantResolutionException if multiple variants tie on score
     */
    fun resolve(
        tenantId: TenantId,
        templateId: TemplateId,
        criteria: VariantSelectionCriteria,
    ): VariantId {
        val variants = ListVariants(tenantId = tenantId, templateId = templateId).query()

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

    private fun matchesAllRequired(variant: TemplateVariant, required: Map<String, String>): Boolean = required.all { (key, value) -> variant.attributes[key] == value }

    private fun countRequiredMatches(variant: TemplateVariant, required: Map<String, String>): Int = required.count { (key, value) -> variant.attributes[key] == value }

    private fun countOptionalMatches(variant: TemplateVariant, optional: Map<String, String>): Int = optional.count { (key, value) -> variant.attributes[key] == value }

    private data class ScoredVariant(val variant: TemplateVariant, val score: Int)
}
