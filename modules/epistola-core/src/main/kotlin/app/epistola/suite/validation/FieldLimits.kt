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
}
