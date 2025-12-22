plugins {
    `java-library`
    id("com.github.node-gradle.node")
}

node {
    download.set(false)
    nodeProjectDir.set(file("${rootProject.projectDir}"))
}

val pnpmBuild by tasks.registering(com.github.gradle.node.pnpm.task.PnpmTask::class) {
    dependsOn(tasks.named("pnpmInstall"))
    args.set(listOf("--filter", "@epistola/vendor", "build"))
    inputs.file("package.json")
    inputs.file("rollup.config.js")
    inputs.dir("entries")
    outputs.dir("dist")
}

val copyDistToResources by tasks.registering(Copy::class) {
    dependsOn(pnpmBuild)
    from("dist")
    into(layout.buildDirectory.dir("resources/main/META-INF/resources/vendor"))
}

tasks.named("processResources") {
    dependsOn(copyDistToResources)
}
