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
    // The shared tenant-snapshot SYNC layer: the port both support features ride (SnapshotSyncPort
    // + hub adapter + no-op fallback), the build/dedup/upload service (TenantSnapshotSyncService),
    // and the last-synced tracking that the upgrading freshness timer reads. The snapshot itself
    // (BuildTenantSnapshot / RestoreTenantSnapshot) lives in epistola-core; this module only moves
    // it to/from the hub. Backups and upgrading depend on this module.

    // Core (snapshot build/restore commands, IDs, mediator, security, AppMetadataService) — api so
    // the snapshot types and the sync service are exposed to the feature modules.
    api(project(":modules:epistola-core"))

    // Commercial support tier (hub client wiring, registration, credentials). Inert until
    // epistola.support.enabled=true. api so the feature modules see InstallationStore etc.
    api(project(":modules:epistola-support"))

    // Epistola Hub client (Kotlin gRPC SDK) — EpistolaHubClient + generated catalog-sync proto for
    // the hub adapter.
    implementation(libs.epistola.hub.client)

    // Spring Boot — base + JDBC/JDBI (AppMetadataService is JDBI-backed via core).
    implementation("org.springframework.boot:spring-boot-starter")

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
