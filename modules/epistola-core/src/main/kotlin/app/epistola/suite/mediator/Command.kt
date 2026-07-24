// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

/**
 * Marker interface for commands that represent state-changing operations.
 * The type parameter R represents the return type of the command handler.
 */
interface Command<out R>
