// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.encryption.EncryptedCredentialColumn
import app.epistola.suite.encryption.EncryptedCredentialContributor
import org.springframework.stereotype.Component

@Component
class ExchangeEncryptedCredentialColumns : EncryptedCredentialContributor {
    override fun columns(): Set<EncryptedCredentialColumn> = setOf(
        EncryptedCredentialColumn("exchange_tenant_connections", "access_token", listOf("tenant_key")),
        EncryptedCredentialColumn("exchange_tenant_connections", "refresh_token", listOf("tenant_key")),
        EncryptedCredentialColumn("exchange_device_authorizations", "device_code", listOf("tenant_key")),
    )
}
