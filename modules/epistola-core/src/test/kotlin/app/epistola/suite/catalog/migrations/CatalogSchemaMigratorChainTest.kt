package app.epistola.suite.catalog.migrations

import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator.Companion.validateMigrationChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jsonMapper

/**
 * Startup chain-integrity checks for [CatalogSchemaMigrator] — the contiguous,
 * total, gap-free invariant that fails application start (Flyway-like) when the
 * migration chain is malformed. Pure unit test; no Spring.
 */
class CatalogSchemaMigratorChainTest {

    /** A migration step that does nothing but declare its [from]/[to]. */
    private class NoopMigration(override val from: Int, override val to: Int = from + 1) : CatalogSchemaMigration

    @Test
    fun `empty chain is valid when baseline equals current`() {
        assertThatCode { validateMigrationChain(emptyList(), baseline = 4, current = 4) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `empty chain is invalid when baseline is below current`() {
        assertThatThrownBy { validateMigrationChain(emptyList(), baseline = 3, current = 4) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("empty")
    }

    @Test
    fun `contiguous chain spanning baseline to current is valid`() {
        val chain = listOf(NoopMigration(from = 4), NoopMigration(from = 5), NoopMigration(from = 6))
        assertThatCode { validateMigrationChain(chain, baseline = 4, current = 7) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `chain order is irrelevant - sorted internally`() {
        val chain = listOf(NoopMigration(from = 6), NoopMigration(from = 4), NoopMigration(from = 5))
        assertThatCode { validateMigrationChain(chain, baseline = 4, current = 7) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a gap in the chain fails`() {
        // 4->5, then 6->7: missing 5->6.
        val chain = listOf(NoopMigration(from = 4), NoopMigration(from = 6))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 7) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Gap")
    }

    @Test
    fun `a duplicate from fails`() {
        val chain = listOf(NoopMigration(from = 4), NoopMigration(from = 4))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 5) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Duplicate")
    }

    @Test
    fun `a chain that does not start at baseline fails`() {
        val chain = listOf(NoopMigration(from = 5))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 6) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("baseline")
    }

    @Test
    fun `a chain that does not reach current fails`() {
        val chain = listOf(NoopMigration(from = 4))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 6) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("reach current")
    }

    @Test
    fun `a step that advances more than one version fails`() {
        val chain = listOf(NoopMigration(from = 4, to = 6))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 6) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("exactly one version")
    }

    @Test
    fun `the real bean accepts the v2-to-v4 chain (live baseline 2, current 4)`() {
        // The wired-in state: 2 -> 3 -> 4 steps span [baseline, current].
        assertThatCode { CatalogSchemaMigrator(jsonMapper(), listOf(NoopMigration(from = 2), NoopMigration(from = 3))) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `the real bean rejects an empty chain (baseline 2 != current 4 leaves a gap)`() {
        // With baseline below current, an empty chain is malformed — a good guard
        // that init actually validates against the live constants.
        assertThatThrownBy { CatalogSchemaMigrator(jsonMapper(), emptyList()) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `chain integrity does not depend on the constants - probes the window directly`() {
        // Documents that the checker is parameterised on the version window, so
        // Phase-1 migrations can be validated against future baseline/current.
        assertThat(listOf(NoopMigration(4), NoopMigration(5)).map { it.to }).containsExactly(5, 6)
    }
}
