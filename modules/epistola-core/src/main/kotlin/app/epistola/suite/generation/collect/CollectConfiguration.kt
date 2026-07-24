// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect

import app.epistola.suite.generation.collect.ring.ConsistentHashRing
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration for the v0.3 generation result collection layer.
 *
 * Exposes shared, stateless helpers as beans so command/query handlers can
 * inject them. Repositories use `@Repository` so they're discovered via
 * component scan; this file is just for the leaf-level pure helpers like
 * [ConsistentHashRing] that don't carry a Spring stereotype themselves.
 */
@Configuration
class CollectConfiguration {

    /**
     * Single, stateless ring instance with the default K=128 virtual nodes.
     * All command handlers share it — assignment is a pure function so
     * sharing is safe.
     */
    @Bean
    fun consistentHashRing(): ConsistentHashRing = ConsistentHashRing()
}
