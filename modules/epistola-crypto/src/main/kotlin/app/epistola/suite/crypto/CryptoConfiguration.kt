// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.crypto

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles

/**
 * Registers [EncryptionProperties] and exposes the singleton [CredentialCipher].
 *
 * The explicit `@EnableConfigurationProperties` mirrors the repo convention
 * (`StorageConfiguration`, `LocaleConfiguration`, …) so the module-level
 * `TestApplication` used by `epistola-core` integration tests picks the
 * properties up without relying on `@ConfigurationPropertiesScan`.
 */
@Configuration
@EnableConfigurationProperties(EncryptionProperties::class)
class CryptoConfiguration {
    @Bean
    fun credentialCipher(properties: EncryptionProperties, environment: Environment): CredentialCipher {
        val isProd = environment.acceptsProfiles(Profiles.of("prod"))
        return CredentialCipherFactory.create(properties, isProd)
    }
}
