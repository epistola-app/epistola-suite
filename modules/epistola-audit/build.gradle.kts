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
    // The whole audit feature lives here: the recorder (a CommandListener/QueryListener),
    // the read model + ListAuditEntries query, the audit_log migration, the UI (handler +
    // routes + templates + nav contributor), and the partition/backup table contributors.
    // The audit *contract* (the listener SPIs, the NotAudited/AuditedRead markers, and the
    // AUDIT_VIEW permission) stays in epistola-core, which core domain types depend on.

    // Core (mediator SPIs, IDs, security, clock, JDBI config) — api so the audit domain
    // types are exposed to the host app, which composes this module's UI.
    api(project(":modules:epistola-core"))

    // Shared web/UI toolkit (HTMX functional-web DSL + Nav SPI) for the audit viewer.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf) + JDBC/JDBI persistence.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Micrometer (audit-persistence failure counter)
    implementation("io.micrometer:micrometer-core")

    // JDBI (audit persistence)
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.postgres)
    implementation(libs.jdbi.spring)

    // Flyway (the audit_log migration lives in this module)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Kotlin (reflection: generic entity-id extraction in the recorder)
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
