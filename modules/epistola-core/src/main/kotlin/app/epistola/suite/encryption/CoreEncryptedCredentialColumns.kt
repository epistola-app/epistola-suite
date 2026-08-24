// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.encryption

import org.springframework.stereotype.Component

@Component
class CoreEncryptedCredentialColumns : EncryptedCredentialContributor {
    override fun columns(): Set<EncryptedCredentialColumn> = setOf(
        EncryptedCredentialColumn("catalogs", "source_auth_credential", listOf("tenant_key", "id")),
        EncryptedCredentialColumn("code_lists", "credential", listOf("tenant_key", "catalog_key", "slug")),
    )
}
