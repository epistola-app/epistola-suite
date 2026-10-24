// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

/**
 * The last migration before themes moved to JSON storage and their table went (ADR 0033,
 * `V20261024114440`). A migration test that checks what an earlier migration did to the `themes`
 * table migrates up to here; `ThemeStorageTest` and `DataPreservationMigrationIT` cover what follows.
 */
internal const val BEFORE_THEME_STORAGE = "20261024110723"
