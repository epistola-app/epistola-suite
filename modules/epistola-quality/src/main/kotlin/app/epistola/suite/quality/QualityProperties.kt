// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Installation settings of the quality feature. Whether a tenant has the feature at all is the
 * `quality` feature toggle, not a property.
 */
@ConfigurationProperties("epistola.quality")
data class QualityProperties(
    /** Check a variant after it is saved. Off leaves "Check now" and marking ready as the only triggers. */
    val checkOnSave: Boolean = true,
    /**
     * How long after a save the check runs. Each save in that time moves it, so a burst of autosaves
     * is checked once, after the last.
     */
    val checkOnSaveDelay: Duration = Duration.ofSeconds(5),
    val languageTool: LanguageToolProperties = LanguageToolProperties(),
)

/**
 * A LanguageTool server the operator runs: an installation provider (ADR 0047). Unused unless [url] is
 * set, and then only for tenants that switch it on (`quality-languagetool`). Template text is sent to
 * it, with every expression replaced by a placeholder, never data.
 */
data class LanguageToolProperties(
    /**
     * The server's base URL, such as `http://languagetool:8010`. Blank: LanguageTool is not used.
     * Read through [configuredUrl]: a key present in YAML with no value binds as an empty string.
     */
    val url: String? = null,
    /** LanguageTool's `level`: `default`, or `picky` for more style rules. */
    val level: String = "default",
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(20),
    /** Text per request; a variant with more is checked in several requests. */
    val maxRequestCharacters: Int = 20_000,
    /** When it runs (ADR 0047): by default on a save, on "Check now" and on a ready mark. */
    val triggers: Set<CheckTrigger> = setOf(CheckTrigger.SAVE, CheckTrigger.EXPLICIT, CheckTrigger.READY),
) {
    val configuredUrl: String? get() = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QualityProperties::class)
class QualityConfiguration
