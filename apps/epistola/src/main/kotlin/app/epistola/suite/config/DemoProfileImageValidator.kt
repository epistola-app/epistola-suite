// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import org.springframework.util.ClassUtils

/**
 * Fails the boot when the `demo` profile is active on an image that does not contain demo mode.
 *
 * Demo mode ships as `modules:epistola-demo`, which is only on the runtime classpath of the
 * `epistola-suite:{version}-demo` image (see `apps/epistola/build.gradle.kts`). That separation is
 * the point: it means no amount of configuration can turn a production install into a demo, because
 * the classes are not there to configure.
 *
 * The failure mode it exists to prevent is the quiet one. Spring is perfectly happy with a profile
 * that no configuration file or bean responds to, so an operator who set `SPRING_PROFILES_ACTIVE=demo`
 * on the default image would get a normal install, no demo data, no explanation — and might well
 * conclude demo mode was broken rather than absent. Refusing to start says which it is.
 *
 * Deliberately keyed off class presence rather than a bean: when the module is absent there is no
 * type left to inject or condition on. Lives in the always-present host app for the same reason.
 *
 * Skipped in `test`, like [app.epistola.suite.security.AuthenticationSafetyValidator].
 */
@Component
@Profile("!test")
class DemoProfileImageValidator(
    private val environment: Environment,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() = validate(javaClass.classLoader)

    /**
     * The class loader is an explicit input so a test can hide the demo package and exercise the
     * failure — the case that only otherwise occurs in a differently-built artifact.
     */
    internal fun validate(classLoader: ClassLoader) {
        if (!environment.acceptsProfiles(Profiles.of(DEMO_PROFILE))) return
        if (ClassUtils.isPresent(DEMO_MARKER_CLASS, classLoader)) return

        throw IllegalStateException(
            "The '$DEMO_PROFILE' profile is active but this image does not contain demo mode. " +
                "Demo mode ships only in epistola-suite:{version}-demo — the default image leaves it " +
                "out on purpose, so that a production install cannot be turned into a demo by " +
                "configuration alone. Either use the -demo image or drop the '$DEMO_PROFILE' profile.",
        )
    }

    companion object {
        private const val DEMO_PROFILE = "demo"

        /**
         * A class from `modules:epistola-demo`. Named as a string rather than referenced, because
         * the whole question is whether it exists. Keep it in step with that module.
         */
        private const val DEMO_MARKER_CLASS = "app.epistola.suite.demo.DemoLoader"
    }
}
