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
    // Core module (provides Installation domain, AppMetadataService, JDBI config).
    // The support module persists credentials through AppMetadataService — it does
    // not talk to JDBI / SQL directly, so it doesn't pull JDBI or Flyway in itself.
    api(project(":modules:epistola-core"))

    // Epistola Hub client (Kotlin gRPC SDK). Bundles its own gRPC + protobuf
    // BOMs via Gradle module metadata so consumers don't need to know any
    // gRPC version coordinates. Drags in grpc-stub, grpc-kotlin-stub,
    // grpc-netty-shaded (runtime), grpc-protobuf, protobuf-kotlin,
    // and kotlinx-serialization-json-jvm. Verified no conflicts with the
    // rest of the suite (no other module uses gRPC).
    implementation(libs.epistola.hub.client)

    // Shared web/UI toolkit (HTMX functional-web DSL + NavContributor SPI). The base support
    // module owns the Support → Overview page and the "Support" nav group.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf) for the Overview page.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation(project(":modules:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
