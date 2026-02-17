package app.epistola.generation.pdf

import app.epistola.template.model.Expression
import app.epistola.template.model.ExpressionLanguage

/**
 * Extracts an [Expression] from a node prop value.
 *
 * In the v2 node/slot model, Expression objects are stored as Maps in [Node.props].
 * This function handles the conversion from the raw Map representation back to an [Expression].
 *
 * @param prop The raw prop value (expected to be a Map with "raw" and optional "language" keys)
 * @param defaultLanguage The default expression language to use when "language" is not specified
 * @return The extracted [Expression], or null if the prop is not a valid expression map
 */
internal fun extractExpression(prop: Any?, defaultLanguage: ExpressionLanguage): Expression? {
    if (prop == null) return null

    @Suppress("UNCHECKED_CAST")
    val expressionMap = prop as? Map<String, Any?> ?: return null

    val raw = expressionMap["raw"] as? String ?: return null
    val language = (expressionMap["language"] as? String)?.let { langStr ->
        try {
            ExpressionLanguage.valueOf(langStr)
        } catch (_: IllegalArgumentException) {
            defaultLanguage
        }
    } ?: defaultLanguage

    return Expression(raw = raw, language = language)
}

/**
 * Filters out null values from a style map.
 *
 * The v2 [Node.styles] is typed as `Map<String, Any?>?` but [StyleApplicator] expects
 * `Map<String, Any>?`. Null values have no meaning in the style cascade, so we filter them out.
 */
internal fun Map<String, Any?>.filterNonNullValues(): Map<String, Any> {
    @Suppress("UNCHECKED_CAST")
    return this.filterValues { it != null } as Map<String, Any>
}
