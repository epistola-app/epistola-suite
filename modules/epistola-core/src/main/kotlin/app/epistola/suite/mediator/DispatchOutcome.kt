// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

/** Whether a dispatched command/query handler returned ([SUCCESS]) or threw ([FAILURE]). */
enum class DispatchOutcome { SUCCESS, FAILURE }
