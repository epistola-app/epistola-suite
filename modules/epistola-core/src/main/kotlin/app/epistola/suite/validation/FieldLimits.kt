package app.epistola.suite.validation

/**
 * Canonical length limits for user-editable text fields, referenced by command
 * validation so a limit lives in one place rather than being repeated across every
 * command. See issue #633.
 *
 * The database columns are a separate (looser) ceiling and the UI templates carry
 * their own `maxlength` literals; the `NameLengthValidationTest` (server) and
 * `InputMaxLengthTest` (UI) guard tests are the cross-layer oracle that keeps the
 * layers aligned.
 */
object FieldLimits {
    /**
     * Max length for display names and titles — tenant, theme, template, stencil
     * and catalog names, plus the template-variant title.
     */
    const val MAX_NAME_LENGTH = 100

    /**
     * Max length for a code-list entry's `code` and `label`, matching the
     * `code_list_entries.code VARCHAR(64)` / `label VARCHAR(200)` columns so an
     * over-length entry is rejected with a field error instead of overflowing the
     * column (SQLSTATE 22001, which would surface as a global 500). See issue #608.
     */
    const val MAX_CODE_LIST_ENTRY_CODE_LENGTH = 64
    const val MAX_CODE_LIST_ENTRY_LABEL_LENGTH = 200
}
