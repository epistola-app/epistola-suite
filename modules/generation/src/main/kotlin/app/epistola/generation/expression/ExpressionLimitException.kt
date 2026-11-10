// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.expression

/**
 * Thrown while rendering when one expression would cost more than it may: run past its time budget,
 * recurse or nest too deeply, or build a result past a built-in's bound (`$pad`'s width). Unlike other
 * expression errors, which render as empty, it fails the render with a message naming the expression:
 * the editor preview shows it to the author, and a document's generation fails with it.
 *
 * An [IllegalArgumentException], so the preview answers it as a refused request, not a server error.
 */
class ExpressionLimitException(
    val reason: String,
    val expression: String? = null,
) : IllegalArgumentException(
    if (expression == null) reason else "The expression `${expression.abbreviated()}` is refused: $reason.",
) {
    /** This refusal, naming [expression]; the innermost name is kept when it already has one. */
    fun forExpression(expression: String): ExpressionLimitException = if (this.expression != null) this else ExpressionLimitException(reason, expression)

    private companion object {
        const val SHOWN = 120

        fun String.abbreviated(): String = if (length <= SHOWN) this else take(SHOWN) + "…"
    }
}
