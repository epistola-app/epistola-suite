// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.CODE_LIST_AT_ADDRESS
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.validation.FieldLimits.MAX_CODE_LIST_ENTRY_CODE_LENGTH
import app.epistola.suite.validation.FieldLimits.MAX_CODE_LIST_ENTRY_LABEL_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle

internal fun validateCodeListEntries(entries: List<CodeListEntry>) {
    validate("entries", entries.all { it.code.isNotBlank() }) { "Entry codes must not be blank" }
    validate("entries", entries.all { it.label.isNotBlank() }) { "Entry labels must not be blank" }
    validate("entries", entries.all { it.code.length <= MAX_CODE_LIST_ENTRY_CODE_LENGTH }) { "Entry codes must be $MAX_CODE_LIST_ENTRY_CODE_LENGTH characters or less" }
    validate("entries", entries.all { it.label.length <= MAX_CODE_LIST_ENTRY_LABEL_LENGTH }) { "Entry labels must be $MAX_CODE_LIST_ENTRY_LABEL_LENGTH characters or less" }
    validate("entries", entries.map { it.code }.toSet().size == entries.size) { "Entry codes must be unique within a code list" }
}
