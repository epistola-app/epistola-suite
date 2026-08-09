// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import ch.qos.logback.core.boolex.PropertyConditionBase

/** Selects Logback's structured console appender when Spring Boot configured a format. */
class StructuredConsoleLoggingCondition : PropertyConditionBase() {
    override fun evaluate(): Boolean = property(CONSOLE_FORMAT_PROPERTY).isNotBlank()

    companion object {
        const val CONSOLE_FORMAT_PROPERTY = "CONSOLE_LOG_STRUCTURED_FORMAT"
    }
}
