// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.security

import jakarta.servlet.Filter

/**
 * Marker for a filter that runs ahead of [ApiKeyAuthenticationFilter] in the `/api` chain.
 *
 * Exists so the host app's security configuration can register such a filter without importing the
 * module that supplies it — which is what lets demo mode ship in a separate image. Implementations
 * are collected as a list and each is added before [ApiKeyAuthenticationFilter]; when none are on
 * the classpath the chain is unchanged.
 *
 * An implementation must either authenticate the request — in practice by publishing a principal on
 * [ApiKeyAuthenticationFilter.REQUEST_ATTR_PRINCIPAL], which that filter reads first — or pass it
 * through untouched. It must not reject: rejecting is the API-key filter's job, and short-circuiting
 * here would change how every unrecognised credential is answered.
 *
 * The only implementation today is the demo shared secret.
 */
interface ApiPreAuthenticationFilter : Filter
