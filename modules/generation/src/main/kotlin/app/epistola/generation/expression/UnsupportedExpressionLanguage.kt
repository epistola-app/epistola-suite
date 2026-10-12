// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.expression

/**
 * Expression languages that the template model can still name but that Epistola no longer
 * evaluates.
 *
 * The template model (generated from `epistola-contract`) still lists `javascript` as an
 * [app.epistola.template.model.ExpressionLanguage] value. The JavaScript engine was removed
 * because it could not bound execution time or memory, so such an expression is rejected
 * explicitly — at write time by the suite's template validation, and at render time here.
 * It is never silently evaluated as another language.
 */
object UnsupportedExpressionLanguages {
    /** The wire value of the removed JavaScript expression language. */
    const val JAVASCRIPT = "javascript"

    /** The message every surface shows for a JavaScript expression. */
    const val JAVASCRIPT_MESSAGE =
        "JavaScript expressions are no longer supported. Rewrite the expression in JSONata."

    /** True when [language] names the removed JavaScript expression language. */
    fun isJavaScript(language: String?): Boolean = language != null && language.trim().equals(JAVASCRIPT, ignoreCase = true)

    /** Throws [UnsupportedExpressionLanguageException] when [language] names JavaScript. */
    fun rejectJavaScript(language: String?) {
        if (isJavaScript(language)) throw UnsupportedExpressionLanguageException(JAVASCRIPT)
    }
}

/**
 * Thrown while rendering when a template contains an expression in a language that is no longer
 * evaluated. Rendering fails rather than guessing at another language.
 */
class UnsupportedExpressionLanguageException(
    val language: String,
) : RuntimeException(
    if (UnsupportedExpressionLanguages.isJavaScript(language)) {
        UnsupportedExpressionLanguages.JAVASCRIPT_MESSAGE
    } else {
        "Expression language '$language' is not supported. Rewrite the expression in JSONata."
    },
)
