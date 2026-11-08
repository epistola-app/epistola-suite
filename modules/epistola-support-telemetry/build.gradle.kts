plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

// The OpenTelemetry the app ships (apps/epistola pins it above Spring Boot's for a CVE), so these
// tests run the exporter the app runs: its class, and so the logger quieted on the telemetry
// threads (SupportTelemetryThreads), moved between the two versions.
extra["opentelemetry.version"] = libs.versions.opentelemetry.get()

the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // The dedicated telemetry leg to the support service (docs/support-protocol.md): a Logback
    // appender for application logs and a Micrometer OTLP registry for metrics, both over OTLP/HTTP
    // with the support connection's bearer token, and only while Epistola grants `telemetry`.

    // Core (NodeIdentity, InstallationProperties, FeatureKey/TenantKey, SecurityContext, EpistolaClock).
    api(project(":modules:epistola-core"))

    // The support connection (scopes, endpoints, bearer token).
    api(project(":modules:epistola-support"))

    // Spring Boot base.
    implementation("org.springframework.boot:spring-boot-starter")

    // Micrometer + its OTLP registry (the dedicated metrics push leg).
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation(libs.otlp.proto)

    // OpenTelemetry SDK + OTLP/HTTP exporter (the logs leg). Versions are managed by Spring Boot's
    // BOM via spring-boot-starter-opentelemetry on the application classpath.
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // FailedExportException: how the log exporter reports the HTTP status of a failed export, read
    // to tell a 403 insufficient_scope (Epistola withdrew telemetry) from any other failure.
    // Already on the runtime classpath through the exporter; named here to compile against it.
    implementation("io.opentelemetry:opentelemetry-exporter-common")

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
    // Decodes the OTLP payloads that reach the stand-in support service.
    testImplementation(libs.otlp.proto)
    // Boot's metrics auto-configuration, to show how the support registry joins the app's meters.
    testImplementation("org.springframework.boot:spring-boot-micrometer-metrics")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
