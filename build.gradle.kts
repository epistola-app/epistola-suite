plugins {
    // kotlin-jvm, ktlint, and kover are on the classpath via buildSrc convention plugins
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.cyclonedx) apply false
    id("org.jetbrains.kotlinx.kover")
}

group = "app.epistola"
version = findProperty("releaseVersion") as String? ?: "dev"
description = "Epistola Document Suite"

// Configure Kover for test coverage — all modules must be listed explicitly for aggregation
dependencies {
    kover(project(":apps:epistola"))
    kover(project(":modules:epistola-core"))
    kover(project(":modules:generation"))
    kover(project(":modules:rest-api"))
    kover(project(":modules:loadtest"))
    kover(project(":modules:feedback"))
}

kover {
    reports {
        total {
            xml {
                onCheck = false
            }
            html {
                onCheck = false
            }
        }
        filters {
            excludes {
                // Exclude Spring Boot AOT generated code
                packages(
                    "org.springframework.*",
                    "io.micrometer.*",
                    "org.flywaydb.*",
                    "com.zaxxer.*",
                )
                // Exclude Spring AOT generated classes
                classes(
                    "*__BeanDefinitions",
                    "*__BeanFactoryRegistrations",
                    "*__TestContext*",
                    "*\$\$*",
                )
            }
        }
    }
}
