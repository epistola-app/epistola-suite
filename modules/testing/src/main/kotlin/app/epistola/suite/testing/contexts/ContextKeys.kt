// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.contexts

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.core.annotation.MergedAnnotations
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy
import org.springframework.test.context.BootstrapUtils
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The Spring test context a test class uses, as the key Spring's context cache stores it under: its
 * [MergedContextConfiguration], worked out the way Spring does it, without starting anything.
 *
 * Null for a class that is not a Spring test, and for one whose configuration cannot be worked out;
 * such a class never has a context closed early on its account.
 */
internal object ContextKeys {
    private val keys = ConcurrentHashMap<Class<*>, Optional<MergedContextConfiguration>>()

    fun of(testClass: Class<*>): MergedContextConfiguration? = keys.computeIfAbsent(testClass) { Optional.ofNullable(compute(it)) }.orElse(null)

    private fun compute(testClass: Class<*>): MergedContextConfiguration? {
        if (!usesSpring(testClass)) return null
        return runCatching { BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration() }.getOrNull()
    }

    /** Extended with [SpringExtension], itself or through an enclosing class (a `@Nested` one). */
    private fun usesSpring(testClass: Class<*>): Boolean = generateSequence(testClass) { it.enclosingClass }.any { candidate ->
        MergedAnnotations.from(candidate, SearchStrategy.TYPE_HIERARCHY).stream(ExtendWith::class.java)
            .anyMatch { SpringExtension::class.java in it.getClassArray("value") }
    }
}
