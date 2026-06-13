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
    // The dedicated OTLP telemetry leg: a custom Logback appender that forwards application-log
    // events to the hub as OTLP log records, plus a dedicated Micrometer OTLP registry for metrics.
    // Both are gated globally on epistola.support.telemetry.enabled AND an installation-wide hub
    // entitlement (support-telemetry). See ADR 0006.

    // Core (NodeIdentity, InstallationProperties, FeatureKey/TenantKey, SecurityContext, EpistolaClock).
    api(project(":modules:epistola-core"))

    // Commercial support tier (EpistolaHubClient + InstallationStore credentials, SupportEntitlementService).
    api(project(":modules:epistola-support"))

    // Epistola Hub client (for the InstallationStore credentials port).
    implementation(libs.epistola.hub.client)

    // Spring Boot base.
    implementation("org.springframework.boot:spring-boot-starter")

    // Micrometer + its OTLP registry (the dedicated metrics push leg).
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-registry-otlp")

    // OpenTelemetry SDK + OTLP/HTTP exporter (the logs leg). Versions are managed by Spring Boot's
    // BOM via spring-boot-starter-opentelemetry on the application classpath.
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Logback — the appender taps log events directly (same approach as epistola-core's appender).
    implementation("ch.qos.logback:logback-classic")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation(project(":modules:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
