plugins {
    `java-library`
}

val buildFrontend by tasks.registering(Exec::class) {
    description = "Builds headless editor frontend assets"
    group = "build"

    workingDir = rootProject.projectDir
    commandLine("pnpm", "--filter", "@epistola/headless-editor", "build")

    inputs.dir(layout.projectDirectory.dir("src"))
    inputs.file(layout.projectDirectory.file("package.json"))
    inputs.file(layout.projectDirectory.file("tsconfig.json"))
    inputs.file(rootProject.layout.projectDirectory.file("pnpm-lock.yaml"))
    outputs.dir(layout.projectDirectory.dir("dist"))
}

val copyDistToResources by tasks.registering(Copy::class) {
    dependsOn(buildFrontend)
    from("dist")
    into(layout.buildDirectory.dir("resources/main/META-INF/resources/headless-editor"))
}

tasks.named("processResources") {
    dependsOn(copyDistToResources)
}

tasks.named("compileJava") {
    dependsOn(copyDistToResources)
}
