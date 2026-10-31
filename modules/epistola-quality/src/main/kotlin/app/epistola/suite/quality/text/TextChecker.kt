// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.i18n.ResolvedLocale
import app.epistola.suite.quality.CheckTrigger
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode

/** Where a checker processes the text it is given (ADR 0044, section 5). */
enum class TextEgress {
    /** Inside this application; the text goes nowhere. */
    IN_PROCESS,

    /** A service the operator runs and configures, such as a self-hosted LanguageTool. */
    INSTALLATION,

    /** A service outside the organisation. Needs each tenant's consent; none exists yet. */
    EXTERNAL,
}

/** What kind of problem a diagnostic is, across checkers, so findings group the same way whichever reported them. */
enum class TextCategory { SPELLING, GRAMMAR, PUNCTUATION, TYPOGRAPHY, STYLE, READABILITY, OTHER }

/**
 * One problem a checker found in a block, at [offset] and [length] in [TextBlock.text]. Shaped after an
 * LSP diagnostic with its quick fixes: the [suggestions] are replacement texts for that range, which the
 * author may take, never applied for them.
 *
 * Stored in the result cache as JSON, so its shape is persisted: add fields with defaults.
 */
data class TextDiagnostic(
    val ruleId: String,
    val category: TextCategory,
    val severity: QualitySeverity,
    val message: String,
    val offset: Int,
    val length: Int,
    val suggestions: List<String> = emptyList(),
    val docsUrl: String? = null,
    /** Why this is a problem: the rule's intent (ADR 0046), shown with the finding. */
    val explanation: String? = null,
)

/** Whether a checker can check text in a locale now, and under which configuration. */
sealed interface TextCheckerReadiness {
    /**
     * It can. [configuration] is part of the result cache's key: everything that changes what the
     * checker would report for the same text (its version, its rule set, its settings) belongs in it,
     * so a change of any of them reads as a cache miss rather than as an old answer.
     */
    data class Ready(val configuration: String) : TextCheckerReadiness

    /** It cannot, for a reason worth telling the author ("not configured", "no language chosen"). */
    data class Skipped(val reason: String) : TextCheckerReadiness
}

/**
 * A language checker (ADR 0044, section 4): a stateless function from block texts to diagnostics.
 *
 * A checker never sees a template, a node id, an expression or data, only [TextBlock.text], with each
 * placeholder as [TextBlock.PLACEHOLDER]. It holds no state that matters: the suite caches its results
 * by the block's hash, so it is asked only about text it has not seen under this configuration, and it
 * submits nothing itself. Each checker is its own quality source: [checkerId] is the source id its
 * findings carry, and reconciliation keeps one checker from resolving another's findings.
 *
 * Implementations are Spring beans, collected by [app.epistola.suite.quality.QualitySourceRegistry].
 */
interface TextChecker {
    val checkerId: QualitySourceId

    val displayName: String

    val egress: TextEgress

    /** When it runs (ADR 0047, section 4): a provider's configuration, never a profile's. */
    val triggers: Set<CheckTrigger>

    /** Whether this installation configured it at all; a checker that is not is skipped as such. */
    val configured: Boolean get() = true

    /**
     * The feature toggle a tenant switches it on with, or null when it is on for every tenant with the
     * quality feature. A stand-in for tenant provider settings (ADR 0047, section 6).
     */
    val tenantSwitch: FeatureKey? get() = null

    /** May call out (to learn a server's version); throwing means the checker is unavailable. */
    fun readiness(locale: ResolvedLocale): TextCheckerReadiness

    /**
     * The diagnostics of each text, in the order given: one list per text, empty when nothing is wrong.
     * Throws when it cannot answer for all of them; nothing is cached or submitted then.
     */
    fun check(
        texts: List<String>,
        locale: ResolvedLocale,
        configuration: String,
    ): List<List<TextDiagnostic>>
}

/**
 * The persisted shape of [TextDiagnostic]s in the result cache, written and read by hand so the stored
 * JSON is a stated format rather than whatever reflection makes of the class. Reading is lenient: an
 * unknown category or severity reads as [TextCategory.OTHER] and [QualitySeverity.parse]'s default.
 */
object TextDiagnosticJson {
    fun write(diagnostics: List<TextDiagnostic>, objectMapper: ObjectMapper): ArrayNode = objectMapper.createArrayNode().apply {
        diagnostics.forEach { d ->
            addObject().apply {
                put("ruleId", d.ruleId)
                put("category", d.category.name)
                put("severity", d.severity.name)
                put("message", d.message)
                put("offset", d.offset)
                put("length", d.length)
                putArray("suggestions").apply { d.suggestions.forEach { add(it) } }
                d.docsUrl?.let { put("docsUrl", it) }
                d.explanation?.let { put("explanation", it) }
            }
        }
    }

    fun read(json: JsonNode): List<TextDiagnostic> = json.toList().map { d ->
        TextDiagnostic(
            ruleId = d.path("ruleId").asString(""),
            category = TextCategory.entries.firstOrNull { it.name == d.path("category").asString("") } ?: TextCategory.OTHER,
            severity = QualitySeverity.parse(d.path("severity").asString("")),
            message = d.path("message").asString(""),
            offset = d.path("offset").asInt(0),
            length = d.path("length").asInt(0),
            suggestions = d.path("suggestions").toList().map { it.asString("") },
            docsUrl = d.path("docsUrl").takeIf { it.isString }?.asString(),
            explanation = d.path("explanation").takeIf { it.isString }?.asString(),
        )
    }
}
