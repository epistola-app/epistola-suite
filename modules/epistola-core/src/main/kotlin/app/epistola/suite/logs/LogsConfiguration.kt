// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.logs

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Wires the `epistola.logs.*` properties for the application-log capture and
 * retention components.
 */
@Configuration
@EnableConfigurationProperties(ApplicationLogProperties::class)
class LogsConfiguration
