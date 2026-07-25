plugins {
    base
    // kotlin-jvm, ktlint, and kover are on the classpath via buildSrc convention plugins
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.cyclonedx) apply false
    alias(libs.plugins.dependency.license.report) apply false
    id("org.jetbrains.kotlinx.kover")
}

group = "app.epistola"
version = findProperty("releaseVersion") as String? ?: (findProperty("version") as String? ?: "dev")
description = "Epistola Document Suite"

// Configure Kover for test coverage — all modules must be listed explicitly for aggregation
dependencies {
    kover(project(":apps:epistola"))
    kover(project(":modules:epistola-core"))
    kover(project(":modules:generation"))
    kover(project(":modules:rest-api"))
    kover(project(":modules:loadtest"))
    kover(project(":modules:epistola-web"))
    kover(project(":modules:epistola-support"))
    kover(project(":modules:epistola-support-feedback"))
    kover(project(":modules:epistola-support-snapshots"))
    kover(project(":modules:epistola-support-backups"))
    kover(project(":modules:epistola-support-upgrading"))
    kover(project(":modules:epistola-mcp"))
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

tasks.register<CheckMigrationVersionsTask>("checkMigrationVersions") {
    description = "Checks that new runtime Flyway migrations append after the target branch head."
    group = "verification"
    repositoryDir.set(layout.projectDirectory)
    explicitBaseRef.set(providers.gradleProperty("migrationVersionBaseRef"))
    envBaseRef.set(providers.environmentVariable("MIGRATION_VERSION_BASE_REF"))
    githubBaseRef.set(providers.environmentVariable("GITHUB_BASE_REF"))
}

tasks.named("check") {
    dependsOn("checkMigrationVersions")
}
