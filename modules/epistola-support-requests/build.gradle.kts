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
    // The full feedback feature lives here: domain (model + commands/queries + migrations),
    // the sync engine (FeedbackSyncPort + drivers + NoOp fallback) and the UI (handlers +
    // templates). The feature is freely usable; only sending it to Epistola needs the support
    // connection.

    // Core (IDs, mediator, security, JDBI config) — api so the feedback domain types are
    // exposed to the host app (which uses them via this module).
    api(project(":modules:epistola-core"))

    // The support connection (registration, tokens, the support service's RestClient). Inert
    // until epistola.support.enabled=true.
    api(project(":modules:epistola-support"))

    // Shared web/UI toolkit (HTMX functional-web DSL) for the feedback UI.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf) + JDBC/JDBI persistence.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Micrometer (feedback-sync outcome metrics)
    implementation("io.micrometer:micrometer-core")

    // JDBI (feedback persistence)
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.postgres)
    implementation(libs.jdbi.spring)

    // Flyway (feedback migrations live in this module)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation(project(":modules:testing"))
    testImplementation(testFixtures(project(":modules:epistola-support")))
    // The requests sent to a partner are checked against docs/support-partner-api.yaml.
    testImplementation(libs.swagger.request.validator)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
