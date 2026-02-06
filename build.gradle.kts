plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.cyclonedx) apply false
    alias(libs.plugins.kover)
}

group = "app.epistola"
version = "0.0.1-SNAPSHOT"
description = "Epistola Document Suite"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

// Configure Kotlin for all Kotlin subprojects
configure(subprojects.filter { it.path.startsWith(":apps") || it.path.startsWith(":modules") }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    configurations {
        named("compileOnly") {
            extendsFrom(configurations.getByName("annotationProcessor"))
        }
    }
}

// Configure Kover for test coverage
dependencies {
    kover(project(":apps:epistola"))
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
