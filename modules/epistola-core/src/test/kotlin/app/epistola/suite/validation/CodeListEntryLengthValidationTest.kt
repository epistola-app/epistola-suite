// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.validation

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.commands.ImportCodeList
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.FieldLimits.MAX_CODE_LIST_ENTRY_CODE_LENGTH
import app.epistola.suite.validation.FieldLimits.MAX_CODE_LIST_ENTRY_LABEL_LENGTH
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-layer oracle for #608 (server side): code-list ENTRY `code`/`label`
 * lengths must be rejected past their DB column ceilings (`code VARCHAR(64)`,
 * `label VARCHAR(200)`) at command construction, so an over-length entry never
 * reaches the database as a SQLSTATE 22001 (which would surface as a global 500).
 *
 * PR #647 aligned the code-list *displayName* (guarded in
 * [NameLengthValidationTest]); this guards the entry rows it did not cover. The
 * expected values (64 / 200) are duplicated here on purpose — this is the
 * independent oracle, so it catches a command drifting from the limit AND an
 * accidental change to the [FieldLimits] constants.
 */
class CodeListEntryLengthValidationTest {

    private val tenantId = TenantId(TenantKey("testtenant"))
    private val catalogId = CatalogId(CatalogKey.DEFAULT, tenantId)
    private val codeListId = CodeListId(CodeListKey.of("mycodelist"), catalogId)

    private fun createWith(entry: CodeListEntry) = CreateCodeList(codeListId, displayName = "Codes", sourceType = CodeListSource.INLINE, entries = listOf(entry))

    private fun updateWith(entry: CodeListEntry) = UpdateCodeList(codeListId, displayName = "Codes", entries = listOf(entry))

    private fun importWith(entry: CodeListEntry) = ImportCodeList(tenantId, CatalogKey.DEFAULT, slug = "mycodelist", displayName = "Codes", entries = listOf(entry))

    @Test
    fun `the canonical code-list entry limits match the DB columns`() {
        assertEquals(64, MAX_CODE_LIST_ENTRY_CODE_LENGTH)
        assertEquals(200, MAX_CODE_LIST_ENTRY_LABEL_LENGTH)
    }

    @Test
    fun `entry code longer than the column limit is rejected on create, update and import`() {
        val overLong = CodeListEntry(code = "x".repeat(65), label = "ok")
        assertEquals("entries", assertFailsWith<ValidationException> { createWith(overLong) }.field)
        assertEquals("entries", assertFailsWith<ValidationException> { updateWith(overLong) }.field)
        assertEquals("entries", assertFailsWith<ValidationException> { importWith(overLong) }.field)
    }

    @Test
    fun `entry label longer than the column limit is rejected on create, update and import`() {
        val overLong = CodeListEntry(code = "ok", label = "x".repeat(201))
        assertEquals("entries", assertFailsWith<ValidationException> { createWith(overLong) }.field)
        assertEquals("entries", assertFailsWith<ValidationException> { updateWith(overLong) }.field)
        assertEquals("entries", assertFailsWith<ValidationException> { importWith(overLong) }.field)
    }

    @Test
    fun `entry code and label at exactly the column limit are accepted`() {
        val atLimit = CodeListEntry(code = "x".repeat(64), label = "y".repeat(200))
        createWith(atLimit)
        updateWith(atLimit)
        importWith(atLimit)
    }
}
