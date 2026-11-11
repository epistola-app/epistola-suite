// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.contexts

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.ClassDescriptor
import org.junit.jupiter.api.ClassOrdererContext
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.util.Optional

/**
 * Which test classes share a Spring context, when the last of them is done, and the order that
 * starts each context's classes together. Nothing here boots a context: the keys are worked out
 * the way Spring's cache does, without starting anything.
 */
class ContextGroupingTest {
    @Configuration
    class AppConfig

    @Configuration
    class OtherConfig

    @SpringJUnitConfig(AppConfig::class)
    class FirstOnApp

    @SpringJUnitConfig(AppConfig::class)
    class SecondOnApp

    @SpringJUnitConfig(AppConfig::class)
    @TestPropertySource(properties = ["feature.enabled=true"])
    class AppWithAProperty

    @SpringJUnitConfig(OtherConfig::class)
    class OnOther

    class NoSpring

    @Test
    fun `classes with the same configuration share a key, and a property or another configuration makes a new one`() {
        assertThat(ContextKeys.of(FirstOnApp::class.java)).isEqualTo(ContextKeys.of(SecondOnApp::class.java)).isNotNull
        assertThat(ContextKeys.of(AppWithAProperty::class.java)).isNotEqualTo(ContextKeys.of(FirstOnApp::class.java))
        assertThat(ContextKeys.of(OnOther::class.java)).isNotEqualTo(ContextKeys.of(FirstOnApp::class.java))
    }

    @Test
    fun `a class that is not a Spring test has no context`() {
        assertThat(ContextKeys.of(NoSpring::class.java)).isNull()
    }

    @Test
    fun `a context is done only after the last class of the run that uses it`() {
        val usage = ContextUsage()
        usage.count(listOf(FirstOnApp::class.java, SecondOnApp::class.java, OnOther::class.java, NoSpring::class.java))

        assertThat(usage.finished(FirstOnApp::class.java)).`as`("one of two on the app context").isFalse()
        assertThat(usage.finished(OnOther::class.java)).`as`("the only class on its context").isTrue()
        assertThat(usage.finished(SecondOnApp::class.java)).`as`("the second of two").isTrue()
        assertThat(usage.finished(NoSpring::class.java)).`as`("no context to close").isFalse()
    }

    @Test
    fun `a class of a context the run did not count never closes it`() {
        val usage = ContextUsage()
        usage.count(listOf(FirstOnApp::class.java))

        assertThat(usage.finished(OnOther::class.java)).isFalse()
    }

    @Test
    fun `before the run is counted no class is ever the last, so every context stays open`() {
        assertThat(ContextUsage().finished(OnOther::class.java)).isFalse()
    }

    @Test
    fun `the orderer starts classes without a context first, then each context's classes together`() {
        val classes = mutableListOf(
            descriptor(FirstOnApp::class.java),
            descriptor(OnOther::class.java),
            descriptor(NoSpring::class.java),
            descriptor(SecondOnApp::class.java),
            descriptor(AppWithAProperty::class.java),
        )

        ContextGroupingClassOrderer().orderClasses(context(classes))

        assertThat(classes.map { it.testClass }).containsExactly(
            NoSpring::class.java,
            FirstOnApp::class.java,
            SecondOnApp::class.java,
            OnOther::class.java,
            AppWithAProperty::class.java,
        )
    }

    private fun descriptor(testClass: Class<*>): ClassDescriptor = object : ClassDescriptor {
        override fun getTestClass(): Class<*> = testClass

        override fun getDisplayName(): String = testClass.simpleName

        override fun isAnnotated(annotationType: Class<out Annotation>): Boolean = testClass.isAnnotationPresent(annotationType)

        override fun <A : Annotation> findAnnotation(annotationType: Class<A>): Optional<A> = Optional.ofNullable(testClass.getAnnotation(annotationType))

        override fun <A : Annotation> findRepeatableAnnotations(annotationType: Class<A>): List<A> = testClass.getAnnotationsByType(annotationType).toList()
    }

    private fun context(classes: MutableList<ClassDescriptor>): ClassOrdererContext = object : ClassOrdererContext {
        override fun getClassDescriptors(): MutableList<out ClassDescriptor> = classes

        override fun getConfigurationParameter(key: String): Optional<String> = Optional.empty()
    }
}
