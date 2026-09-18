// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
     *
     * This is the **interactive** (typed-input) ceiling, deliberately tighter than
     * the underlying columns (see below). Import-shaped write paths use the wider
     * column ceilings instead, so machine-sourced content that predates or exceeds
     * this UX cap still round-trips (see issue #692).
     */
    const val MAX_NAME_LENGTH = 100

    /**
     * DB column ceiling for the `name`/`title` columns — `themes.name`,
     * `stencils.name`, `document_templates.name`, `template_variants.title`,
     * `assets.name`, `catalogs.name` are all `VARCHAR(255)`.
     *
     * Used by non-interactive **import** paths (catalog ZIP, remote manifest,
     * snapshot restore) which must accept anything the column accepts rather than
     * the tighter interactive [MAX_NAME_LENGTH]; over-length values are rejected
     * with a clear per-resource error instead of overflowing the column (SQLSTATE
     * 22001 → opaque 500). See issue #692. Rejecting exactly at the column width is
     * behaviour-preserving: values that fit the column today keep importing.
     */
    const val MAX_NAME_COLUMN_LENGTH = 255

    /**
     * DB column ceiling for the `display_name` columns —
     * `variant_attribute_definitions.display_name` and `code_lists.display_name`
     * are `VARCHAR(100)`. Import-path counterpart to [MAX_NAME_COLUMN_LENGTH] for
     * the narrower display-name columns. See issue #692.
     */
    const val MAX_DISPLAY_NAME_COLUMN_LENGTH = 100

    /**
     * Max length for a code-list entry's `code` and `label`, matching the
     * `code_list_entries.code VARCHAR(64)` / `label VARCHAR(200)` columns so an
     * over-length entry is rejected with a field error instead of overflowing the
     * column (SQLSTATE 22001, which would surface as a global 500). See issue #608.
     */
    const val MAX_CODE_LIST_ENTRY_CODE_LENGTH = 64
    const val MAX_CODE_LIST_ENTRY_LABEL_LENGTH = 200

    /**
     * Catalog keywords are author-supplied free text, stored in a `jsonb` column that imposes no
     * ceiling of its own, and they travel with the catalog to Exchange. Unbounded, a single
     * keyword long enough to have nothing to wrap on stretched the whole catalog page sideways —
     * measured at 218px of document overflow before `.badge-list` started truncating.
     *
     * The display no longer depends on this being small, so the limit is about what a keyword is
     * *for*: a short discovery term someone searches on, not a phrase and not a sentence. Twenty of
     * them is already more than any catalog in practice.
     */
    const val MAX_KEYWORD_LENGTH = 20
    const val MAX_KEYWORDS = 20
}
