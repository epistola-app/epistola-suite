// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.i18n

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Application-wide default locale. Tenants may override this value per tenant
 * (`tenants.default_locale`); when their override is NULL, [defaultLocale]
 * applies. Format is a BCP-47 language tag (e.g. `en-US`, `nl-NL`); valid
 * values are sourced from the bundled `system.bcp-47` code list.
 */
@ConfigurationProperties(prefix = "epistola.i18n")
data class LocaleProperties(
    val defaultLocale: String = "en-US",
)
