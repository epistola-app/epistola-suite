// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.crypto

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for credential encryption at rest.
 *
 * - [enabled] — master switch. When `false`, [CredentialCipher] is a pure
 *   pass-through (values are stored as plaintext). Intended only for local
 *   development / explicit opt-out.
 * - [primaryKeyId] — the key id used for **all new encryptions**. Must be
 *   present in [keys].
 * - [keys] — the keyset. Every key (the primary plus any retired-but-retained
 *   keys) is available for **decryption**, selected by the key id embedded in
 *   the ciphertext envelope. Rotation = add a new key, point [primaryKeyId] at
 *   it, then re-encrypt and finally retire the old key. See `docs/encryption.md`.
 *
 * When [keys] is empty and [enabled] is true, behaviour depends on the active
 * Spring profile: the `prod` profile fails fast; other profiles fall back to an
 * ephemeral in-memory dev key (data is not readable across restarts).
 */
@ConfigurationProperties(prefix = "epistola.encryption")
data class EncryptionProperties(
    val enabled: Boolean = true,
    val primaryKeyId: String = "",
    val keys: List<KeyMaterial> = emptyList(),
) {
    /**
     * A single keyset entry. [material] is the base64 (standard, with or without
     * padding) encoding of exactly 32 random bytes (a 256-bit AES key); generate
     * with `openssl rand -base64 32`. [id] is an opaque label embedded in every
     * ciphertext produced under this key — it must not contain `:`.
     */
    data class KeyMaterial(
        val id: String = "",
        val material: String = "",
    )
}
