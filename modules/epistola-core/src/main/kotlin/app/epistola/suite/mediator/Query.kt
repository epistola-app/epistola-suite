// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

/**
 * Marker interface for queries that represent read-only operations.
 * The type parameter R represents the return type of the query handler.
 */
interface Query<out R>
