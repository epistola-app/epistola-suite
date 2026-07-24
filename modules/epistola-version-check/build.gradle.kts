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
    // OSS release/version-check feature: a clustered daily check against a public releases.json,
    // cached in app_metadata, surfaced on the tenant home page via the epistola-web HomeNotice SPI.
    // Independent of the commercial support hub.

    // Core — AppMetadataService, EpistolaClock, SemVersion, cluster schedules, InstallationService,
    // mediator. Internal use only (the host app talks to this feature through the HomeNotice SPI).
    implementation(project(":modules:epistola-core"))

    // Shared web/UI toolkit — the HomeNoticeContributor SPI + UiRequestContext.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (RestClient/webmvc + Thymeleaf fragment).
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing — the evaluator/client/schedule unit tests (no DB, no Spring context).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
