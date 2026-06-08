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
    // The Backups feature: the daily retained-snapshot scheduler and the Backups UI (list,
    // "back up now", restore). It rides the shared snapshot sync; the snapshot itself is built in
    // epistola-core and moved to/from the hub by epistola-support-snapshots. Gated by the
    // `support-backups` feature toggle and (for the hub calls) `epistola.support.enabled`.

    // Core (IDs, mediator, security, feature toggles, scheduler lock, JDBI config).
    api(project(":modules:epistola-core"))

    // Shared snapshot sync (TenantSnapshotSyncService + SnapshotSyncPort + system principal).
    // api-exposes epistola-support, so the support tier is on the classpath transitively.
    api(project(":modules:epistola-support-snapshots"))

    // Epistola Hub client — only for the typed HubEntitlementDeniedException the UI catches to
    // render the "no service contract" state.
    implementation(libs.epistola.hub.client)

    // Shared web/UI toolkit (HTMX functional-web DSL) for the Backups UI.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf) + JDBC/JDBI persistence.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // JDBI (tenant enumeration in the daily scheduler)
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.postgres)
    implementation(libs.jdbi.spring)

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
