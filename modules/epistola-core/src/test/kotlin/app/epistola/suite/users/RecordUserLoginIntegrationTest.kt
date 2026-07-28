// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.users

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.users.commands.CreateUser
import app.epistola.suite.users.commands.RecordUserLogin
import app.epistola.suite.users.queries.GetUserByExternalId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordUserLoginIntegrationTest : IntegrationTestBase() {

    @Test
    fun `record login refreshes mutable profile attributes on the same user`(): Unit = withMediator {
        val externalId = "oidc-${UUID.randomUUID()}"
        val created = CreateUser(
            externalId = externalId,
            email = "old@example.com",
            displayName = "Old Name",
            provider = AuthProvider.GENERIC_OIDC,
        ).execute()

        RecordUserLogin(
            userId = created.id,
            email = "new@example.com",
            displayName = "New Name",
        ).execute()

        val refreshed = GetUserByExternalId(externalId, AuthProvider.GENERIC_OIDC).query()
        assertThat(refreshed).isNotNull
        assertThat(refreshed!!.id).isEqualTo(created.id)
        assertThat(refreshed.email).isEqualTo("new@example.com")
        assertThat(refreshed.displayName).isEqualTo("New Name")
        assertThat(refreshed.lastLoginAt).isNotNull()
    }
}
