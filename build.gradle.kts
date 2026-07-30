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

val checkContractVersionAlignment = tasks.register("checkContractVersionAlignment") {
    description = "Checks that backend, frontend, and lockfile Epistola contract versions match."
    group = "verification"

    val versionCatalog = layout.projectDirectory.file("gradle/libs.versions.toml")
    val editorPackage = layout.projectDirectory.file("modules/editor/package.json")
    val pnpmLockfile = layout.projectDirectory.file("pnpm-lock.yaml")
    inputs.files(versionCatalog, editorPackage, pnpmLockfile)

    doLast {
        fun requireVersion(
            source: String,
            pattern: Regex,
            description: String,
        ): String = pattern.find(source)?.groupValues?.get(1)
            ?: throw GradleException("Could not find $description while checking Epistola contract version alignment.")

        val versionCatalogText = versionCatalog.asFile.readText()
        val backendVersion = requireVersion(
            versionCatalogText,
            Regex("""(?m)^epistola-contract = "([^"]+)"$"""),
            "epistola-contract in ${versionCatalog.asFile}",
        )
        val backendVersionRefs = linkedMapOf(
            "generated REST server API" to requireVersion(
                versionCatalogText,
                Regex("""(?m)^epistola-server-restapi = .*version\.ref = "([^"]+)".*$"""),
                "epistola-server-restapi version.ref in ${versionCatalog.asFile}",
            ),
            "Kotlin epistola-catalog" to requireVersion(
                versionCatalogText,
                Regex("""(?m)^epistola-catalog = .*version\.ref = "([^"]+)".*$"""),
                "epistola-catalog version.ref in ${versionCatalog.asFile}",
            ),
        )
        val unexpectedVersionRefs = backendVersionRefs.filterValues { it != "epistola-contract" }
        if (unexpectedVersionRefs.isNotEmpty()) {
            val details = unexpectedVersionRefs.entries.joinToString("\n") { (name, versionRef) ->
                "  - $name uses version.ref '$versionRef'"
            }
            throw GradleException(
                "Backend Epistola contract artifacts must share version.ref 'epistola-contract':\n$details",
            )
        }
        val frontendVersion = requireVersion(
            editorPackage.asFile.readText(),
            Regex(""""@epistola\.app/epistola-catalog": "([^"]+)""""),
            "@epistola.app/epistola-catalog in ${editorPackage.asFile}",
        )
        val lockfile = pnpmLockfile.asFile.readText()
        val lockfileImporterVersion = requireVersion(
            lockfile,
            Regex(
                """(?m)^      '@epistola\.app/epistola-catalog':\n""" +
                    """        specifier: ([^\s]+)\n""" +
                    """        version: \1$""",
            ),
            "the aligned editor dependency in ${pnpmLockfile.asFile}",
        )
        val lockfilePackageVersions = Regex(
            """(?m)^  '@epistola\.app/epistola-catalog@([^']+)':(?: \{\})?$""",
        ).findAll(lockfile).map { it.groupValues[1] }.toSet()
        if (lockfilePackageVersions.isEmpty()) {
            throw GradleException(
                "Could not find @epistola.app/epistola-catalog package entries in ${pnpmLockfile.asFile}.",
            )
        }

        val versions = linkedMapOf(
            "Gradle epistola-contract" to setOf(backendVersion),
            "editor @epistola.app/epistola-catalog" to setOf(frontendVersion),
            "pnpm importer" to setOf(lockfileImporterVersion),
            "pnpm package entries" to lockfilePackageVersions,
        )
        if (versions.values.flatten().toSet().size != 1) {
            val details = versions.entries.joinToString("\n") { (name, values) ->
                "  - $name: ${values.joinToString()}"
            }
            throw GradleException(
                "Epistola contract dependencies must use one version across backend and frontend:\n$details",
            )
        }
    }
}

tasks.named("check") {
    dependsOn("checkMigrationVersions", checkContractVersionAlignment)
}
