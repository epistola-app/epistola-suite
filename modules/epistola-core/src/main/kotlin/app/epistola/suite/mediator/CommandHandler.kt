// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

/**
 * Handler interface for processing commands.
 *
 * @param C the command type
 * @param R the result type
 */
interface CommandHandler<C : Command<R>, R> {
    fun handle(command: C): R
}
