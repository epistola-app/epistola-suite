// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.crypto.Secret
import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.StatementContext
import java.sql.ResultSet
import java.sql.Types

/**
 * Transparent encryption-at-rest for [Secret] columns.
 *
 * [SecretArgumentFactory] encrypts on the way into the database; [SecretColumnMapper]
 * decrypts on the way out (passing legacy plaintext through unchanged). Domain types
 * keep using `Secret?` fields and ordinary `SELECT *` / bind sites — the crypto is
 * invisible above the JDBI layer. Registered in [JdbiConfig].
 */
class SecretArgumentFactory(
    private val cipher: CredentialCipher,
) : AbstractArgumentFactory<Secret>(Types.VARCHAR) {
    override fun build(value: Secret, config: ConfigRegistry): Argument = Argument { position, statement, _ ->
        statement.setString(position, cipher.encrypt(value.value))
    }
}

class SecretColumnMapper(
    private val cipher: CredentialCipher,
) : ColumnMapper<Secret> {
    override fun map(r: ResultSet, columnNumber: Int, ctx: StatementContext): Secret? {
        val value = r.getString(columnNumber)
        return if (r.wasNull()) null else Secret(cipher.decrypt(value))
    }
}
