// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import org.openapitools.configuration.EnumConverterConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Registers the contract's enum converters, so a query parameter such as `?status=released` binds
 * by its wire value. Without them Spring converts by constant name (`RELEASED`) and answers 400. The
 * generated configuration lives outside the scanned packages, so it is imported here.
 */
@Configuration(proxyBeanMethods = false)
@Import(EnumConverterConfiguration::class)
class ApiEnumConversion
