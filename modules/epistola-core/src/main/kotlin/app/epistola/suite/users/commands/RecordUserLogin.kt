// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.users.commands

import app.epistola.suite.common.NotAudited
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/**
 * Records a successful identity-provider login.
 *
 * The external subject remains the stable account key; email and display name
 * are mutable profile attributes and are refreshed from the latest login.
 */
data class RecordUserLogin(
    val userId: UserKey,
    val email: String,
    val displayName: String,
) : Command<Unit>,
    SystemInternal,
    NotAudited

@Component
class RecordUserLoginHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RecordUserLogin, Unit> {
    override fun handle(command: RecordUserLogin) {
        jdbi.withHandleUnchecked { handle ->
            handle.createUpdate(
                """
                UPDATE users
                SET email = :email,
                    display_name = :displayName,
                    last_login_at = NOW()
                WHERE id = :userId
                """,
            )
                .bind("userId", command.userId.value)
                .bind("email", command.email)
                .bind("displayName", command.displayName)
                .execute()
        }
    }
}
