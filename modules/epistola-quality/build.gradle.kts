// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // The quality-checks feature: the findings ledger (model + commands/queries + migrations),
    // the QualityFindingSource SPI and its in-process sources, and the report/editor UI.
    //
    // It lives outside epistola-core deliberately. Core must never call quality — the pipeline
    // emits, this module subscribes — and depending on core in this direction makes that a
    // compile-time fact rather than a convention. An OSS feature module, not support-tier: the
    // ledger and its in-process sources work with the support tier off, so it does not depend
    // on epistola-support.

    // Core — mediator, security, IDs, EpistolaClock, feature toggles, the backup-table SPI, and the
    // template queries this module reads through. `api` so the host app can use the ledger's types.
    api(project(":modules:epistola-core"))

    // Shared web/UI toolkit — the HTMX DSL + NavContributor SPI for the quality report UI.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf) + JDBC/JDBI persistence.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // JDBI (the ledger's own tables)
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.postgres)
    implementation(libs.jdbi.spring)

    // Flyway (quality migrations live in this module)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Template model types (TemplateDocument/Node) — a source inspects the node graph.
    implementation(libs.epistola.model)

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation(project(":modules:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
