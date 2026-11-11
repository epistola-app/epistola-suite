// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.contexts

import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.ClassOrdererContext

/**
 * Starts the test classes that share a Spring context one after another: first the classes without
 * one, then each context's classes together, contexts in the order their first class came.
 *
 * With [ReleaseContextWhenDoneExtension] closing a context after its last class, a context is then
 * alive only while its own classes run, instead of from its first class to a last one somewhere
 * at the end of the run. Classes run concurrently, so this is the order they start in, not a strict
 * sequence, and `@Isolated` classes still run on their own at the end. Configured as
 * `junit.jupiter.testclass.order.default` in `junit-platform.properties`.
 */
class ContextGroupingClassOrderer : ClassOrderer {
    override fun orderClasses(context: ClassOrdererContext) {
        val groups = HashMap<Any, Int>()
        val group = context.classDescriptors.associateWith { descriptor ->
            ContextKeys.of(descriptor.testClass)?.let { key -> groups.getOrPut(key) { groups.size } } ?: -1
        }
        context.classDescriptors.sortWith(compareBy { group.getValue(it) })
    }
}
