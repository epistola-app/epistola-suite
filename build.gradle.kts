plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.node.gradle) apply false
    alias(libs.plugins.cyclonedx) apply false
}

group = "app.epistola"
version = "0.0.1-SNAPSHOT"
description = "Epistola Document Suite"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

// Configure Kotlin for all Kotlin subprojects
configure(subprojects.filter { it.path.startsWith(":apps") }) {
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
