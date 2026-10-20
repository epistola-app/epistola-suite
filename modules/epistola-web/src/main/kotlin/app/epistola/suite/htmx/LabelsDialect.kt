// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import org.springframework.stereotype.Component
import org.thymeleaf.context.IExpressionContext
import org.thymeleaf.dialect.AbstractDialect
import org.thymeleaf.dialect.IExpressionObjectDialect
import org.thymeleaf.expression.IExpressionObjectFactory

/**
 * Words for codes on screen. `#labels.of(status)` turns an enum constant or a code like
 * `IN_PROGRESS` into "In progress", so a page never shows a constant's name.
 */
object Labels {
    fun of(value: Any?): String {
        val code = when (value) {
            null -> return ""
            is Enum<*> -> value.name
            else -> value.toString()
        }
        return code.replace('_', ' ').replace('-', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
}

/** Registers [Labels] as `#labels` in every template. */
@Component
class LabelsDialect :
    AbstractDialect("epistola-labels"),
    IExpressionObjectDialect {
    override fun getExpressionObjectFactory(): IExpressionObjectFactory = object : IExpressionObjectFactory {
        override fun getAllExpressionObjectNames(): Set<String> = setOf("labels")

        override fun buildObject(context: IExpressionContext, expressionObjectName: String): Any? = if (expressionObjectName == "labels") Labels else null

        override fun isCacheable(expressionObjectName: String): Boolean = true
    }
}
