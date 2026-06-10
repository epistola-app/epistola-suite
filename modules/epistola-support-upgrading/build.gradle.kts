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
    // The compatibility ("Upgrading") feature: reads the company-side compatibility-check results
    // for the tenant's catalogs and shows them, and owns a freshness sweep that makes a snapshot
    // when none was made recently. A separate feature from Backups; both ride the shared snapshot
    // sync but this one only reads results and tops up snapshot freshness.

    // Core (IDs, mediator, security, feature toggles, scheduler lock, JDBI config).
    api(project(":modules:epistola-core"))

    // Shared snapshot sync (TenantSnapshotSyncService + system principal) for the freshness sweep.
    // api-exposes epistola-support, so the support tier is on the classpath transitively.
    api(project(":modules:epistola-support-snapshots"))

    // Epistola Hub client (Kotlin gRPC SDK) — listCompatibilityResults + HubEntitlementDeniedException.
    implementation(libs.epistola.hub.client)

    // Shared web/UI toolkit (HTMX functional-web DSL) for the Upgrading UI.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf) + JDBC/JDBI persistence.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // JDBI (tenant enumeration in the freshness sweep)
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
