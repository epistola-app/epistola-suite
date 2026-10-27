// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

/**
 * The last migration of 1.x (1.1.0): where a test of the 2.0 upgrade seeds a 1.x database, in the 1.x
 * tables' own shape, before migrating to the end. The 2.0 chain runs from here straight to the 2.0
 * schema (docs/migrations.md, "The 2.0 chain"); it has no intermediate state worth stopping at.
 */
internal const val LAST_1X = "20260923150918"
