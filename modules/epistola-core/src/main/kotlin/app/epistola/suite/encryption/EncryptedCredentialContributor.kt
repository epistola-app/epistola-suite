// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.encryption

/** A domain-owned declaration consumed by the generic credential rotation machinery. */
interface EncryptedCredentialContributor {
    fun columns(): Set<EncryptedCredentialColumn>
}

data class EncryptedCredentialColumn(
    val table: String,
    val column: String,
    val keyColumns: List<String>,
) {
    val qualifiedName: String get() = "$table.$column"

    init {
        val identifier = Regex("^[a-z][a-z0-9_]*$")
        require(identifier.matches(table) && identifier.matches(column) && keyColumns.all(identifier::matches)) {
            "Credential column declarations must contain only trusted SQL identifiers"
        }
        require(keyColumns.isNotEmpty()) { "A credential column must declare its row key" }
    }
}
