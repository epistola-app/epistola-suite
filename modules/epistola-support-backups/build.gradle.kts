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
    // The catalog-backups feature lives here: tenant-snapshot building (reusing the catalog
    // export), the sync engine (BackupSyncPort + hub adapter + no-op fallback), the daily
    // scheduler, restore, and the UI. The capability is gated by the `support-backups`
    // feature toggle and (for the hub calls) `epistola.support.enabled`.

    // Core (catalog export/import, IDs, mediator, security, JDBI config) — api so the
    // backup domain types are exposed to the host app.
    api(project(":modules:epistola-core"))

    // Commercial support tier (hub client wiring, registration, credentials). Inert until
    // epistola.support.enabled=true.
    api(project(":modules:epistola-support"))

    // Epistola Hub client (Kotlin gRPC SDK) — EpistolaHubClient + generated catalog-sync proto.
    implementation("app.epistola.hub:client:0.3.0")

    // Shared web/UI toolkit (HTMX functional-web DSL) for the backups + upgrading UI.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf) + JDBC/JDBI persistence.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // JDBI (tenant enumeration in the scheduler + the destructive wipe during restore)
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.postgres)
    implementation(libs.jdbi.spring)

    // Jackson (snapshot manifest serialization)
    implementation("tools.jackson.module:jackson-module-kotlin")

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
