// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Registers [SupportProperties] whether or not the support connection is on: the connection bean
 * always exists (`Disabled` when it is off), so every module can inject it unconditionally. Only
 * the schedulers that talk to the support service are conditional on `epistola.support.enabled`.
 */
@Configuration
@EnableConfigurationProperties(SupportProperties::class)
class SupportConfiguration
